package com.yourcompany.javaflow.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.yourcompany.javaflow.model.FlowGraph
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * IntelliJ 하단 Tool Window: 분석 결과 표시 + XML 복사/열기 버튼
 */
class FlowToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = createWelcomePanel()
        val content = ContentFactory.getInstance().createContent(panel, "Flow Diagram", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun createWelcomePanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = EmptyBorder(10, 10, 10, 10)
            add(
                JLabel(
                    "<html><b>JavaFlow Visualizer</b><br/><br/>" +
                            "Controller 메서드에서 우클릭 → <i>Generate Flow Diagram (draw.io)</i> 를 실행하세요.<br/>" +
                            "분석 결과가 여기에 표시됩니다.</html>"
                ),
                BorderLayout.CENTER
            )
        }
    }

    companion object {
        private const val TOOL_WINDOW_ID = "JavaFlow"

        /**
         * 분석 완료 후 Tool Window에 결과를 표시합니다.
         */
        fun showResult(project: Project, graph: FlowGraph, xml: String, filePath: String) {
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID) ?: return
            toolWindow.contentManager.removeAllContents(true)

            val panel = buildResultPanel(graph, xml, filePath, project)
            val content = ContentFactory.getInstance().createContent(panel, graph.title, false)
            toolWindow.contentManager.addContent(content)
            toolWindow.show()
        }

        private fun buildResultPanel(
            graph: FlowGraph,
            xml: String,
            filePath: String,
            project: Project
        ): JPanel {
            return JPanel(BorderLayout(0, 8)).apply {
                border = EmptyBorder(10, 10, 10, 10)

                // 상단: 요약 정보
                val summary = JLabel(
                    "<html><b>분석 완료: ${escapeHtml(graph.title)}</b><br/>" +
                            "노드: ${graph.nodes.size}개 &nbsp;|&nbsp; 연결: ${graph.edges.size}개<br/>" +
                            "<font color='#555555'>저장: ${escapeHtml(filePath)}</font></html>"
                )
                add(summary, BorderLayout.NORTH)

                // 중간: XML 미리보기
                val xmlArea = JTextArea(xml).apply {
                    isEditable = false
                    font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 11)
                    lineWrap = false
                }
                val scrollPane = JScrollPane(xmlArea).apply {
                    preferredSize = Dimension(800, 300)
                }
                add(scrollPane, BorderLayout.CENTER)

                // 하단: 버튼 패널
                val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))

                // XML 클립보드 복사
                val copyBtn = JButton("XML 복사 (draw.io 붙여넣기)").apply {
                    toolTipText = "생성된 XML을 클립보드에 복사합니다. draw.io > Extras > Edit Diagram에 붙여넣으세요."
                    addActionListener {
                        val sel = StringSelection(xml)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                        JOptionPane.showMessageDialog(null, "clipboard에 복사되었습니다!")
                    }
                }
                buttonPanel.add(copyBtn)

                // 파일 탐색기에서 열기
                val openBtn = JButton("파일 위치 열기").apply {
                    addActionListener {
                        try {
                            java.awt.Desktop.getDesktop().open(java.io.File(filePath).parentFile)
                        } catch (ex: Exception) {
                            JOptionPane.showMessageDialog(null, "파일 탐색기를 열 수 없습니다: ${ex.message}")
                        }
                    }
                }
                buttonPanel.add(openBtn)

                // 노드 목록 표시 버튼
                val listBtn = JButton("노드 목록 보기").apply {
                    addActionListener {
                        val sb = StringBuilder("<html><table border='1' cellpadding='3'>")
                        sb.append("<tr><th>레이어</th><th>이름</th><th>상세</th></tr>")
                        graph.nodes.forEach { node ->
                            sb.append("<tr><td>${node.type}</td><td>${escapeHtml(node.label)}</td><td>${escapeHtml(node.detail)}</td></tr>")
                        }
                        sb.append("</table></html>")
                        val label = JLabel(sb.toString())
                        JOptionPane.showMessageDialog(null, JScrollPane(label), "노드 목록", JOptionPane.PLAIN_MESSAGE)
                    }
                }
                buttonPanel.add(listBtn)

                add(buttonPanel, BorderLayout.SOUTH)
            }
        }

        private fun escapeHtml(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
