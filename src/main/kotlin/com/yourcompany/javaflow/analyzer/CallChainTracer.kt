package com.yourcompany.javaflow.analyzer

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.yourcompany.javaflow.model.*
import com.yourcompany.javaflow.analyzer.DbAccessDetector

/**
 * PSI를 사용해 메서드 호출 체인을 DFS로 추적합니다.
 *
 * - 순환 호출 방지 (visited set)
 * - 최대 깊이 제한 (기본 15)
 * - 인터페이스 → 구현체 자동 해석 (레거시 Service 인터페이스 패턴 지원)
 */
object CallChainTracer {

    private const val MAX_DEPTH = 15

    // DB 접근 레이어로 간주할 최상위 인터페이스
    private val DB_SUPER_INTERFACES = setOf(
        "org.springframework.data.repository.Repository",
        "org.springframework.data.jpa.repository.JpaRepository",
        "org.springframework.data.jpa.repository.CrudRepository",
        "org.springframework.data.repository.CrudRepository",
        "org.springframework.data.repository.PagingAndSortingRepository"
    )

    /**
     * entryMethod에서 시작하여 전체 호출 체인을 graph에 기록합니다.
     */
    fun trace(
        project: Project,
        entryMethod: PsiMethod,
        graph: FlowGraph
    ) {
        val entryNode = EntryPointFinder.buildEntryNode(entryMethod, graph)
        traceMethod(project, entryMethod, entryNode.id, graph, mutableSetOf(), 0)
        addResponseNode(entryMethod, entryNode.id, graph)
    }

    private fun traceMethod(
        project: Project,
        method: PsiMethod,
        parentNodeId: String,
        graph: FlowGraph,
        visited: MutableSet<String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        val signature = methodSignature(method)
        if (signature in visited) return
        visited.add(signature)

        // 메서드 바디에서 모든 메서드 호출 수집
        val body = method.body ?: return
        val callExpressions = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)

        for (call in callExpressions) {
            val resolved = call.resolveMethod() ?: continue
            val resolvedClass = resolved.containingClass ?: continue

            // 같은 클래스의 private 헬퍼 메서드는 추적하지 않음
            if (resolvedClass == method.containingClass && resolved.hasModifierProperty(PsiModifier.PRIVATE)) {
                continue
            }

            // 표준 라이브러리 / java.* 는 스킵
            val qualifiedName = resolvedClass.qualifiedName ?: continue
            val packageName = (resolvedClass.containingFile as? PsiJavaFile)?.packageName ?: ""
            
            if (isUninteresting(resolvedClass, resolved, qualifiedName, packageName)) continue

            // DB 접근 및 매퍼 레이어 판별
            if (DbAccessDetector.isMapStruct(resolvedClass) || DbAccessDetector.isDbAccessClass(resolvedClass)) {
                val nodeType = if (DbAccessDetector.isMapStruct(resolvedClass)) NodeType.MAPSTRUCT else NodeType.REPOSITORY
                val dbNode = DbAccessDetector.buildDbNode(resolved, resolvedClass, graph)
                // buildDbNode는 기본적으로 REPOSITORY 타입을 쓰므로, MAPSTRUCT면 타입을 바꿔줌
                val updatedNode = dbNode.copy(type = nodeType)
                graph.addNode(updatedNode) // 이미 있으면 덮어씌움 (id 동일)
                
                addEdgeIfNew(parentNodeId, dbNode.id, "calls", graph)

                // SQL 추출 (MapStruct는 제외)
                if (nodeType == NodeType.REPOSITORY) {
                    val sqlNode = SqlExtractor.extractSql(resolved, resolvedClass, graph)
                    if (sqlNode != null) {
                        addEdgeIfNew(dbNode.id, sqlNode.id, "executes", graph)
                    }
                }

                // MapStruct → 반환 타입 MODEL 노드 추가
                if (nodeType == NodeType.MAPSTRUCT) {
                    addMapStructModelNode(resolved, dbNode.id, graph)
                }
                continue
            }

            // Service 레이어 (또는 일반 Bean 호출)
            val nodeType = determineNodeType(resolvedClass)
            val nodeId = "node_${qualifiedName}_${resolved.name}_${depth}"
            if (!graph.hasNode(nodeId)) {
                val node = FlowNode(
                    id = nodeId,
                    label = "${resolvedClass.name}.${resolved.name}()",
                    type = nodeType,
                    className = qualifiedName,
                    methodName = resolved.name
                )
                graph.addNode(node)
            }
            addEdgeIfNew(parentNodeId, nodeId, "calls", graph)

            // 재귀적으로 호출 추적
            // 인터페이스 메서드인 경우 구현체 탐색
            val implMethod = findImplementation(resolved) ?: resolved
            
            traceMethod(project, implMethod, nodeId, graph, visited, depth + 1)
            
            // 만약 추적 후에도 해당 노드에서 뻗어나가는 엣지가 없다면? (이건 재귀에서 처리하는게 나음)
        }
        
        // 버그 3: 로깅만 있거나 외부 호출이 없는 메서드 처리
        checkEmptyCalls(parentNodeId, graph)
    }

    private fun isUninteresting(cls: PsiClass, method: PsiMethod, fqn: String, pkg: String): Boolean {
        // 1. 표준 라이브러리 및 공통 라이브러리
        if (fqn.startsWith("java.") || fqn.startsWith("javax.") || 
            fqn.startsWith("org.slf4j") || fqn.startsWith("org.apache.commons") ||
            fqn.startsWith("org.springframework.ui") || fqn.startsWith("org.springframework.validation")
        ) return true

        // 2. 패키지 기반 필터링 (DTO, Model 등)
        if (pkg.contains(".dto") || pkg.endsWith(".model") || pkg.contains(".domain.model")) return true

        // 3. 클래스 이름 Suffix 기반 필터링
        val name = cls.name ?: ""
        val suffixes = listOf("Dto", "VO", "Model", "Request", "Response", "Entity", "Value", "Wrapper")
        if (suffixes.any { name.endsWith(it) }) return true

        // 4. 단순 Getter/Setter 필터링
        if (isSimpleAccessor(method)) return true

        return false
    }

    private fun isSimpleAccessor(method: PsiMethod): Boolean {
        val name = method.name
        if (!name.startsWith("get") && !name.startsWith("set") && !name.startsWith("is")) return false
        
        val body = method.body ?: return true // 세부 구현 없으면 단순 접근자로 간주
        val statements = body.statements
        if (statements.size > 1) return false
        
        // 리턴문 하나거나 필드 대입 하나인 경우
        return true
    }

    private fun checkEmptyCalls(nodeId: String, graph: FlowGraph) {
        // SQL 노드나 이미 "외부 호출 없음"이 달린 노드는 제외
        if (nodeId.startsWith("sql_") || nodeId.endsWith("_empty")) return
        
        val hasOutgoing = graph.edges.any { it.fromId == nodeId }
        if (!hasOutgoing) {
            val emptyNodeId = "${nodeId}_empty"
            if (!graph.hasNode(emptyNodeId)) {
                graph.addNode(FlowNode(
                    id = emptyNodeId,
                    label = "외부 호출 없음 (Internal Only / Log)",
                    type = NodeType.UNKNOWN
                ))
            }
            addEdgeIfNew(nodeId, emptyNodeId, "", graph)
        }
    }

    /**
     * 인터페이스 메서드 → 구현체 메서드 탐색
     * 레거시 코드에서 ServiceImpl 패턴을 처리하기 위해 필요
     */
    private fun findImplementation(method: PsiMethod): PsiMethod? {
        val containingClass = method.containingClass ?: return null
        if (!containingClass.isInterface) return null

        var result: PsiMethod? = null
        MethodReferencesSearch.search(method, false).forEach { ref ->
            if (result != null) return@forEach
            val element = ref.element.parent
            if (element is PsiMethod && element.containingClass?.isInterface == false) {
                result = element
                // Stop searching logic by ignoring subsequent elements
            }
        }
        return result
    }

    private fun determineNodeType(psiClass: PsiClass): NodeType {
        val annotations = psiClass.annotations.map { it.qualifiedName }
        val className = psiClass.name ?: ""
        val packageName = (psiClass.containingFile as? PsiJavaFile)?.packageName ?: ""

        return when {
            annotations.any { it == "org.springframework.stereotype.Repository" } -> NodeType.REPOSITORY
            // Web Service 판별: 클래스명에 WebService 포함 또는 web 패키지의 Service
            annotations.any { it == "org.springframework.stereotype.Service" } &&
                (className.contains("WebService") || packageName.contains(".web.")) -> NodeType.WEB_SERVICE
            annotations.any { it == "org.springframework.stereotype.Service" } -> NodeType.SERVICE
            annotations.any { it == "org.springframework.stereotype.Component" } -> NodeType.SERVICE
            else -> NodeType.UNKNOWN
        }
    }

    private fun methodSignature(method: PsiMethod): String {
        return "${method.containingClass?.qualifiedName}#${method.name}"
    }

    /**
     * MapStruct 매퍼 메서드의 반환 타입을 MODEL 노드로 추가합니다.
     */
    private fun addMapStructModelNode(method: PsiMethod, mapperNodeId: String, graph: FlowGraph) {
        val returnType = method.returnType ?: return
        val typeName = returnType.presentableText
        if (typeName == "void" || typeName.isBlank()) return

        val returnClass = (returnType as? PsiClassType)?.resolve()
        val fqn = returnClass?.qualifiedName ?: typeName
        val modelNodeId = "model_$fqn"

        if (!graph.hasNode(modelNodeId)) {
            graph.addNode(FlowNode(
                id = modelNodeId,
                label = typeName,
                type = NodeType.MODEL,
                detail = "MapStruct 변환 결과",
                className = fqn
            ))
        }
        addEdgeIfNew(mapperNodeId, modelNodeId, "returns", graph)
    }

    /**
     * 진입점 메서드의 반환 타입을 RESPONSE 노드로 추가합니다.
     * ResponseEntity<T> 의 경우 제네릭 타입 T를 표시합니다.
     */
    private fun addResponseNode(entryMethod: PsiMethod, entryNodeId: String, graph: FlowGraph) {
        val returnType = entryMethod.returnType ?: return
        val typeName = returnType.presentableText
        if (typeName == "void" || typeName.isBlank()) return

        val displayName = if (typeName.startsWith("ResponseEntity<") && typeName.endsWith(">")) {
            typeName.removePrefix("ResponseEntity<").removeSuffix(">")
        } else {
            typeName
        }

        val responseNodeId = "response_$entryNodeId"
        graph.addNode(FlowNode(
            id = responseNodeId,
            label = displayName,
            type = NodeType.RESPONSE,
            detail = if (typeName.startsWith("ResponseEntity")) typeName else ""
        ))
        addEdgeIfNew(entryNodeId, responseNodeId, "returns", graph)
    }

    private fun addEdgeIfNew(fromId: String, toId: String, label: String, graph: FlowGraph) {
        val edgeId = "edge_${fromId}_${toId}"
        if (graph.edges.none { it.id == edgeId }) {
            graph.addEdge(FlowEdge(edgeId, fromId, toId, label))
        }
    }
}
