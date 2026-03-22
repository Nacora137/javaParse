package com.yourcompany.javaflow.analyzer

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.yourcompany.javaflow.model.FlowGraph
import com.yourcompany.javaflow.model.FlowNode
import com.yourcompany.javaflow.model.NodeType

/**
 * 진입점 탐지기 — HTTP Controller 외 전문통신 패턴도 지원합니다.
 *
 * [지원 진입점 유형]
 * 1. HTTP     : @Controller / @RestController + @RequestMapping 계열
 * 2. MQ/메시지: @JmsListener, @RabbitListener, @KafkaListener, @SqsListener
 * 3. 스케줄러  : @Scheduled, @EnableScheduling 구현 메서드
 * 4. Batch    : Spring Batch Tasklet.execute(), ItemReader/Writer/Processor
 * 5. 전문통신  : 사용자 정의 어노테이션 / 특정 상위 클래스 상속 패턴
 *               → CustomEntryPointConfig 에 추가 가능
 */
object EntryPointFinder {

    // ─── HTTP Controller ───────────────────────────────────────────
    private val CONTROLLER_ANNOTATIONS = listOf(
        "org.springframework.stereotype.Controller",
        "org.springframework.web.bind.annotation.RestController"
    )

    private val HTTP_MAPPING_ANNOTATIONS = listOf(
        "org.springframework.web.bind.annotation.RequestMapping",
        "org.springframework.web.bind.annotation.GetMapping",
        "org.springframework.web.bind.annotation.PostMapping",
        "org.springframework.web.bind.annotation.PutMapping",
        "org.springframework.web.bind.annotation.DeleteMapping",
        "org.springframework.web.bind.annotation.PatchMapping"
    )

    // ─── MQ / 메시지 리스너 ────────────────────────────────────────
    private val MQ_LISTENER_ANNOTATIONS = listOf(
        "org.springframework.jms.annotation.JmsListener",          // ActiveMQ/IBM MQ
        "org.springframework.amqp.rabbit.annotation.RabbitListener",
        "org.springframework.kafka.annotation.KafkaListener",
        "io.awspring.cloud.sqs.annotation.SqsListener",
        "io.awspring.cloud.messaging.listener.annotation.SqsListener",
        // IBM MQ (레거시 패턴)
        "com.ibm.mq.spring.boot.MQQueueMessageListener"
    )

    // ─── 스케줄러 ─────────────────────────────────────────────────
    private val SCHEDULER_ANNOTATIONS = listOf(
        "org.springframework.scheduling.annotation.Scheduled",
        "org.springframework.scheduling.annotation.EnableScheduling"
    )

    // ─── 전문통신 상위 클래스 패턴 (이름 suffix 매칭) ────────────────
    // 예: XxxHandler, XxxProcessor, XxxService, XxxAction 등
    // 프레임워크마다 다르므로 이름 기반 + 사용자 설정으로 확장
    private val JEONMUN_CLASS_SUFFIXES = listOf(
        "Handler", "Processor", "Action", "Command", "Worker",
        "Listener", "Consumer", "Receiver", "Router", "Dispatcher"
    )

    // ─── Spring Batch ─────────────────────────────────────────────
    private val BATCH_INTERFACES = listOf(
        "org.springframework.batch.core.step.tasklet.Tasklet",
        "org.springframework.batch.item.ItemReader",
        "org.springframework.batch.item.ItemWriter",
        "org.springframework.batch.item.ItemProcessor"
    )

    // ─── 사용자 추가 전문통신 어노테이션 (런타임 설정 가능) ──────────
    val customAnnotations: MutableList<String> = mutableListOf()
    val customSuperClasses: MutableList<String> = mutableListOf()

    // ══════════════════════════════════════════════════════════════
    //  공개 API
    // ══════════════════════════════════════════════════════════════

    /**
     * 프로젝트 전체에서 모든 유형의 진입점 메서드를 수집합니다.
     */
    fun findAllEntryPoints(project: Project): List<EntryPointInfo> {
        val result = mutableListOf<EntryPointInfo>()
        val facade = JavaPsiFacade.getInstance(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)

        result += findHttpEntryPoints(project, facade, projectScope, allScope)
        result += findMqListeners(project, facade, projectScope, allScope)
        result += findScheduledMethods(project, facade, projectScope, allScope)
        result += findBatchEntryPoints(project, facade, projectScope, allScope)
        result += findJeonmunEntryPoints(project, facade, projectScope, allScope)

        return result.distinctBy { "${it.method.containingClass?.qualifiedName}#${it.method.name}" }
    }

    /**
     * 특정 PsiMethod가 어떤 종류의 진입점인지 판별합니다.
     * null 이면 진입점이 아님.
     */
    fun detectEntryPointType(method: PsiMethod): EntryPointType? {
        return when {
            hasMappingAnnotation(method)         -> EntryPointType.HTTP
            hasMqAnnotation(method)              -> EntryPointType.MQ
            hasSchedulerAnnotation(method)       -> EntryPointType.SCHEDULER
            isBatchMethod(method)                -> EntryPointType.BATCH
            isJeonmunMethod(method)              -> EntryPointType.JEONMUN
            hasCustomAnnotation(method)          -> EntryPointType.JEONMUN
            else                                 -> null
        }
    }

    fun buildEntryNode(method: PsiMethod, graph: FlowGraph): FlowNode {
        val type = detectEntryPointType(method) ?: EntryPointType.HTTP
        val info = describeEntryPoint(method, type)
        val nodeId = "entry_${method.containingClass?.qualifiedName}_${method.name}"
        val node = FlowNode(
            id = nodeId,
            label = "${method.containingClass?.name}.${method.name}()",
            type = NodeType.ENTRY_POINT,
            detail = info,
            className = method.containingClass?.qualifiedName ?: "",
            methodName = method.name
        )
        if (!graph.hasNode(nodeId)) graph.addNode(node)
        return node
    }

    // 기존 hasMappingAnnotation 유지 (GenerateFlowAction 에서 사용)
    fun hasMappingAnnotation(method: PsiMethod): Boolean =
        method.annotations.any { ann -> HTTP_MAPPING_ANNOTATIONS.any { it == ann.qualifiedName } }

    fun extractHttpInfo(method: PsiMethod): String {
        val classPath = method.containingClass
            ?.getAnnotation("org.springframework.web.bind.annotation.RequestMapping")
            ?.let { extractMappingValue(it) } ?: ""

        for (ann in method.annotations) {
            val httpMethod = when (ann.qualifiedName) {
                "org.springframework.web.bind.annotation.GetMapping"    -> "GET"
                "org.springframework.web.bind.annotation.PostMapping"   -> "POST"
                "org.springframework.web.bind.annotation.PutMapping"    -> "PUT"
                "org.springframework.web.bind.annotation.DeleteMapping" -> "DELETE"
                "org.springframework.web.bind.annotation.PatchMapping"  -> "PATCH"
                "org.springframework.web.bind.annotation.RequestMapping" -> {
                    ann.findAttributeValue("method")?.text
                        ?.replace("RequestMethod.", "")?.trim('{', '}') ?: "ANY"
                }
                else -> continue
            }
            val methodPath = extractMappingValue(ann)
            return "$httpMethod $classPath$methodPath"
        }
        return "UNKNOWN"
    }

    // ══════════════════════════════════════════════════════════════
    //  내부 탐색 로직
    // ══════════════════════════════════════════════════════════════

    private fun findHttpEntryPoints(
        project: Project, facade: JavaPsiFacade, projectScope: GlobalSearchScope, allScope: GlobalSearchScope
    ): List<EntryPointInfo> {
        val result = mutableListOf<EntryPointInfo>()
        for (annotationFqn in CONTROLLER_ANNOTATIONS) {
            val annClass = facade.findClass(annotationFqn, allScope) ?: continue
            for (psiClass in AnnotatedElementsSearch.searchPsiClasses(annClass, projectScope).toList()) {
                for (method in psiClass.methods) {
                    if (hasMappingAnnotation(method)) {
                        result.add(EntryPointInfo(method, EntryPointType.HTTP, extractHttpInfo(method)))
                    }
                }
            }
        }
        return result
    }

    private fun findMqListeners(
        project: Project, facade: JavaPsiFacade, projectScope: GlobalSearchScope, allScope: GlobalSearchScope
    ): List<EntryPointInfo> {
        val result = mutableListOf<EntryPointInfo>()
        for (fqn in MQ_LISTENER_ANNOTATIONS + customAnnotations) {
            val annClass = facade.findClass(fqn, allScope) ?: continue
            for (method in AnnotatedElementsSearch.searchPsiMethods(annClass, projectScope).toList()) {
                val destination = extractDestination(method, fqn)
                result.add(EntryPointInfo(method, EntryPointType.MQ, "MQ: $destination"))
            }
        }
        return result
    }

    private fun findScheduledMethods(
        project: Project, facade: JavaPsiFacade, projectScope: GlobalSearchScope, allScope: GlobalSearchScope
    ): List<EntryPointInfo> {
        val result = mutableListOf<EntryPointInfo>()
        val fqn = SCHEDULER_ANNOTATIONS[0] // @Scheduled
        val annClass = facade.findClass(fqn, allScope) ?: return result
        for (method in AnnotatedElementsSearch.searchPsiMethods(annClass, projectScope).toList()) {
            val cron = method.getAnnotation(fqn)
                ?.findAttributeValue("cron")?.text?.trim('"') ?: ""
            val fixedRate = method.getAnnotation(fqn)
                ?.findAttributeValue("fixedRate")?.text?.trim('"') ?: ""
            val detail = when {
                cron.isNotBlank() -> "cron: $cron"
                fixedRate.isNotBlank() -> "fixedRate: ${fixedRate}ms"
                else -> "Scheduler"
            }
            result.add(EntryPointInfo(method, EntryPointType.SCHEDULER, detail))
        }
        return result
    }

    private fun findBatchEntryPoints(
        project: Project, facade: JavaPsiFacade, projectScope: GlobalSearchScope, allScope: GlobalSearchScope
    ): List<EntryPointInfo> {
        val result = mutableListOf<EntryPointInfo>()
        val batchMethodNames = listOf("execute", "read", "write", "process")
        for (interfaceFqn in BATCH_INTERFACES) {
            val interfaceClass = facade.findClass(interfaceFqn, allScope) ?: continue
            for (implClass in ClassInheritorsSearch.search(interfaceClass, projectScope, true).toList()) {
                for (method in implClass.methods) {
                    if (!method.hasModifierProperty(PsiModifier.ABSTRACT) &&
                        method.name in batchMethodNames
                    ) {
                        result.add(EntryPointInfo(
                            method, EntryPointType.BATCH,
                            "Batch: ${interfaceClass.name}.${method.name}()"
                        ))
                    }
                }
            }
        }
        return result
    }

    /**
     * 전문통신 패턴 탐지:
     * - 특정 suffix 클래스 (Handler, Processor, …)의 public 메서드
     * - 사용자 정의 상위 클래스 상속 패턴
     */
    private fun findJeonmunEntryPoints(
        project: Project, facade: JavaPsiFacade, projectScope: GlobalSearchScope, allScope: GlobalSearchScope
    ): List<EntryPointInfo> {
        val result = mutableListOf<EntryPointInfo>()

        // 사용자 정의 상위 클래스 기반
        for (superFqn in customSuperClasses) {
            val superClass = facade.findClass(superFqn, allScope) ?: continue
            for (implClass in ClassInheritorsSearch.search(superClass, projectScope, true).toList()) {
                for (method in implClass.methods) {
                    if (method.hasModifierProperty(PsiModifier.PUBLIC) &&
                        !method.hasModifierProperty(PsiModifier.ABSTRACT) &&
                        !method.hasModifierProperty(PsiModifier.STATIC)
                    ) {
                        result.add(EntryPointInfo(
                            method, EntryPointType.JEONMUN,
                            "전문: ${superClass.name} impl"
                        ))
                    }
                }
            }
        }
        return result
    }

    private fun hasMqAnnotation(method: PsiMethod): Boolean =
        method.annotations.any { ann -> MQ_LISTENER_ANNOTATIONS.any { it == ann.qualifiedName } }

    private fun hasSchedulerAnnotation(method: PsiMethod): Boolean =
        method.annotations.any { ann -> SCHEDULER_ANNOTATIONS.any { it == ann.qualifiedName } }

    private fun hasCustomAnnotation(method: PsiMethod): Boolean =
        customAnnotations.isNotEmpty() &&
                method.annotations.any { ann -> customAnnotations.any { it == ann.qualifiedName } }

    private fun isBatchMethod(method: PsiMethod): Boolean {
        val cls = method.containingClass ?: return false
        val batchMethodNames = setOf("execute", "read", "write", "process")
        if (method.name !in batchMethodNames) return false
        return BATCH_INTERFACES.any { fqn ->
            cls.isInheritor(
                JavaPsiFacade.getInstance(cls.project)
                    .findClass(fqn, GlobalSearchScope.allScope(cls.project)) ?: return@any false,
                true
            )
        }
    }

    private fun isJeonmunMethod(method: PsiMethod): Boolean {
        val cls = method.containingClass ?: return false
        val name = cls.name ?: return false
        return JEONMUN_CLASS_SUFFIXES.any { name.endsWith(it) } &&
                method.hasModifierProperty(PsiModifier.PUBLIC) &&
                !method.hasModifierProperty(PsiModifier.STATIC) &&
                !method.isConstructor
    }

    private fun describeEntryPoint(method: PsiMethod, type: EntryPointType): String {
        return when (type) {
            EntryPointType.HTTP      -> extractHttpInfo(method)
            EntryPointType.MQ        -> {
                val dest = MQ_LISTENER_ANNOTATIONS.mapNotNull { fqn ->
                    method.getAnnotation(fqn)?.let { extractDestination(method, fqn) }
                }.firstOrNull() ?: "unknown queue"
                "MQ: $dest"
            }
            EntryPointType.SCHEDULER -> "Scheduler"
            EntryPointType.BATCH     -> "Batch"
            EntryPointType.JEONMUN   -> "전문통신"
        }
    }

    private fun extractDestination(method: PsiMethod, annotationFqn: String): String {
        val ann = method.getAnnotation(annotationFqn) ?: return "unknown"
        val dest = ann.findAttributeValue("destination")
            ?: ann.findAttributeValue("queues")
            ?: ann.findAttributeValue("topics")
            ?: ann.findAttributeValue("value")
        return dest?.text?.trim('"', '{', '}') ?: "unknown"
    }

    private fun extractMappingValue(annotation: PsiAnnotation): String {
        val valueAttr = annotation.findAttributeValue("value")
            ?: annotation.findAttributeValue("path")
        return when (val text = valueAttr?.text) {
            null -> ""
            else -> text.trim('"', '{', '}').split(",").firstOrNull()?.trim('"') ?: ""
        }
    }
}

// ──────────────────────────────────────────────────────────────────
//  보조 데이터 클래스
// ──────────────────────────────────────────────────────────────────

enum class EntryPointType(val displayName: String) {
    HTTP("HTTP Controller"),
    MQ("MQ / 메시지 리스너"),
    SCHEDULER("Scheduler"),
    BATCH("Spring Batch"),
    JEONMUN("전문통신")
}

data class EntryPointInfo(
    val method: PsiMethod,
    val type: EntryPointType,
    val detail: String           // 예: "GET /api/foo", "MQ: TX_QUEUE", "cron: 0 0 * * *"
)
