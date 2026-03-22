package com.yourcompany.javaflow.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.yourcompany.javaflow.analyzer.CallChainTracer
import com.yourcompany.javaflow.analyzer.EntryPointFinder
import com.yourcompany.javaflow.generator.DrawioXmlGenerator
import com.yourcompany.javaflow.model.FlowGraph
import com.yourcompany.javaflow.ui.FlowToolWindowFactory
import java.io.File

/**
 * 프로젝트 전체 Controller를 분석하여 엔드포인트별 .drawio 파일을 일괄 생성합니다.
 * Tools 메뉴에서 실행
 */
class GenerateAllFlowsAction : AnAction() {

    private val log = Logger.getInstance(GenerateAllFlowsAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "JavaFlow: 전체 흐름 분석 중...") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "Controller 메서드 탐색 중..."

                val entryPoints = com.intellij.openapi.application.runReadAction {
                    EntryPointFinder.findAllEntryPoints(project)
                }
                if (entryPoints.isEmpty()) {
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        Messages.showWarningDialog(
                            project,
                            "프로젝트에서 @Controller / @RestController 메서드를 찾을 수 없습니다.",
                            "JavaFlow Visualizer"
                        )
                    }
                    return
                }

                val outputDir = File(project.basePath, "flow_diagrams").also { it.mkdirs() }
                var processed = 0
                val failed = mutableListOf<String>()

                for (entry in entryPoints) {
                    val methodLabel = com.intellij.openapi.application.runReadAction {
                        "${entry.method.containingClass?.name}.${entry.method.name}"
                    }
                    try {
                        val (xml, info) = com.intellij.openapi.application.runReadAction {
                            val httpInfo = EntryPointFinder.extractHttpInfo(entry.method)
                            val graph = FlowGraph(title = httpInfo)
                            CallChainTracer.trace(project, entry.method, graph)
                            Pair(DrawioXmlGenerator.generate(graph), httpInfo)
                        }

                        indicator.text = "분석 완료: $info"
                        val baseName = info.replace(Regex("[^a-zA-Z0-9가-힣_\\-]"), "_")
                        val outputFile = File(outputDir, "flow_${baseName}.drawio")
                        outputFile.writeText(xml, Charsets.UTF_8)
                        processed++
                    } catch (ex: Exception) {
                        log.warn("JavaFlow: $methodLabel 분석 실패", ex)
                        failed.add("$methodLabel — ${ex.javaClass.simpleName}: ${ex.message}")
                    }
                }

                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    val failedMsg = if (failed.isEmpty()) "" else
                        "\n\n실패 (${failed.size}개):\n" + failed.joinToString("\n") { "• $it" }
                    Messages.showInfoMessage(
                        project,
                        "${processed}개 엔드포인트 분석 완료.\n" +
                                "파일 저장 위치: ${outputDir.absolutePath}$failedMsg",
                        "JavaFlow Visualizer ✓"
                    )
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
