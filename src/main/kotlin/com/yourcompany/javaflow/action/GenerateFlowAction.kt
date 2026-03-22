package com.yourcompany.javaflow.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.yourcompany.javaflow.analyzer.CallChainTracer
import com.yourcompany.javaflow.analyzer.EntryPointFinder
import com.yourcompany.javaflow.analyzer.EntryPointType
import com.yourcompany.javaflow.generator.DrawioXmlGenerator
import com.yourcompany.javaflow.model.FlowGraph
import com.yourcompany.javaflow.ui.FlowToolWindowFactory
import java.io.File

/**
 * 단일 메서드 흐름 분석 액션
 *
 * - HTTP @Mapping 메서드 → 그대로 분석
 * - 일반 메서드 (전문통신 Handler 등) → 확인 다이얼로그 후 분석
 * - 어느 경우든 커서가 메서드 안에 있으면 실행 가능
 */
class GenerateFlowAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        // 커서 위치의 PsiMethod 탐색
        val offset = editor.caretModel.offset
        var element: PsiElement? = psiFile.findElementAt(offset)
        var method: PsiMethod? = null
        while (element != null) {
            if (element is PsiMethod) { method = element; break }
            element = element.parent as? PsiElement
        }

        if (method == null) {
            Messages.showWarningDialog(
                project,
                "커서를 분석하려는 메서드 안에 위치시킨 후 실행해 주세요.",
                "JavaFlow Visualizer"
            )
            return
        }

        // 진입점 유형 판별
        val entryType = EntryPointFinder.detectEntryPointType(method)

        // 인식된 진입점이 아닌 경우 → 사용자에게 확인
        if (entryType == null) {
            val proceed = Messages.showYesNoDialog(
                project,
                "'${method.name}()' 은(는) 자동으로 인식된 진입점이 아닙니다.\n\n" +
                        "• HTTP 매핑 어노테이션 없음\n" +
                        "• MQ/Scheduler/Batch 어노테이션 없음\n\n" +
                        "그래도 이 메서드부터 흐름을 분석하시겠습니까?\n" +
                        "(전문통신 직접 호출 메서드라면 '예'를 선택하세요)",
                "진입점 미인식 — 계속하시겠습니까?",
                Messages.getQuestionIcon()
            )
            if (proceed != Messages.YES) return
        }

        val typeLabel = entryType?.displayName ?: "직접 지정"
        val targetMethod = method

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "JavaFlow: 흐름 분석 중...") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "[$typeLabel] ${targetMethod.name} 분석 중..."

                val title = buildTitle(targetMethod, entryType)
                val graph = FlowGraph(title = title)

                CallChainTracer.trace(project, targetMethod, graph)

                indicator.text = "draw.io XML 생성 중..."
                val xml = DrawioXmlGenerator.generate(graph)

                val baseName = title.replace(Regex("[^a-zA-Z0-9가-힣_\\-]"), "_")
                val outputFile = File(project.basePath, "flow_${baseName}.drawio")
                outputFile.writeText(xml, Charsets.UTF_8)

                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    FlowToolWindowFactory.showResult(project, graph, xml, outputFile.absolutePath)
                    Messages.showInfoMessage(
                        project,
                        "[$typeLabel] 다이어그램 저장 완료:\n${outputFile.absolutePath}\n\n" +
                                "draw.io에서 파일을 열거나, 하단 'JavaFlow' 패널에서 XML 복사 후 붙여넣기 하세요.",
                        "JavaFlow Visualizer ✓"
                    )
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = project != null && editor != null
    }

    private fun buildTitle(method: PsiMethod, entryType: EntryPointType?): String {
        return when (entryType) {
            EntryPointType.HTTP      -> EntryPointFinder.extractHttpInfo(method)
            EntryPointType.MQ        -> "MQ_${method.containingClass?.name}_${method.name}"
            EntryPointType.SCHEDULER -> "Scheduled_${method.name}"
            EntryPointType.BATCH     -> "Batch_${method.name}"
            EntryPointType.JEONMUN   -> "전문_${method.containingClass?.name}_${method.name}"
            null                     -> "${method.containingClass?.name}_${method.name}"
        }
    }
}
