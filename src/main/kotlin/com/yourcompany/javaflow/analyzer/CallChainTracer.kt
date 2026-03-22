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
            if (qualifiedName.startsWith("java.") ||
                qualifiedName.startsWith("javax.") ||
                qualifiedName.startsWith("org.slf4j") ||
                qualifiedName.startsWith("org.apache.commons")
            ) continue

            // DB 접근 레이어 판별
            if (DbAccessDetector.isDbAccessClass(resolvedClass)) {
                val dbNode = DbAccessDetector.buildDbNode(resolved, resolvedClass, graph)
                addEdgeIfNew(parentNodeId, dbNode.id, "calls", graph)

                // SQL 추출
                val sqlNode = SqlExtractor.extractSql(resolved, resolvedClass, graph)
                if (sqlNode != null) {
                    addEdgeIfNew(dbNode.id, sqlNode.id, "executes", graph)
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
            val implMethod = findImplementation(project, resolved) ?: resolved
            traceMethod(project, implMethod, nodeId, graph, visited, depth + 1)
        }
    }

    /**
     * 인터페이스 메서드 → 구현체 메서드 탐색
     * 레거시 코드에서 ServiceImpl 패턴을 처리하기 위해 필요
     */
    private fun findImplementation(project: Project, method: PsiMethod): PsiMethod? {
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
        return when {
            annotations.any { it == "org.springframework.stereotype.Service" } -> NodeType.SERVICE
            annotations.any { it == "org.springframework.stereotype.Repository" } -> NodeType.REPOSITORY
            annotations.any { it == "org.springframework.stereotype.Component" } -> NodeType.SERVICE
            else -> NodeType.UNKNOWN
        }
    }

    private fun methodSignature(method: PsiMethod): String {
        return "${method.containingClass?.qualifiedName}#${method.name}"
    }

    private fun addEdgeIfNew(fromId: String, toId: String, label: String, graph: FlowGraph) {
        val edgeId = "edge_${fromId}_${toId}"
        if (graph.edges.none { it.id == edgeId }) {
            graph.addEdge(FlowEdge(edgeId, fromId, toId, label))
        }
    }
}
