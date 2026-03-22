package com.yourcompany.javaflow.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.yourcompany.javaflow.analyzer.EntryPointFinder
import javax.swing.*
import javax.swing.border.TitledBorder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets

/**
 * Settings > Tools > JavaFlow Visualizer
 *
 * 전문통신 커스텀 진입점 설정:
 *  - 커스텀 어노테이션 FQN 목록
 *  - 커스텀 상위 클래스 FQN 목록
 */
class JeonmunConfigurable : SearchableConfigurable, Configurable {

    private var panel: JPanel? = null

    private val annotationModel = DefaultListModel<String>()
    private val superClassModel = DefaultListModel<String>()

    override fun getId() = "com.yourcompany.javaflow.settings"
    override fun getDisplayName() = "JavaFlow Visualizer"

    override fun createComponent(): JComponent {
        val root = JPanel(BorderLayout(0, 12)).also { panel = it }

        root.add(buildInfoPanel(), BorderLayout.NORTH)

        val center = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.BOTH
            weightx = 1.0
            weighty = 0.5
            insets = Insets(4, 0, 4, 0)
        }

        gbc.gridy = 0; center.add(buildListPanel(
            "커스텀 진입점 어노테이션 (전문통신 프레임워크 어노테이션 FQN)",
            annotationModel,
            "예: com.yourcompany.framework.JeonmunHandler"
        ), gbc)

        gbc.gridy = 1; center.add(buildListPanel(
            "커스텀 상위 클래스 (전문통신 상위 클래스 FQN)",
            superClassModel,
            "예: com.yourcompany.framework.AbstractTxProcessor"
        ), gbc)

        root.add(center, BorderLayout.CENTER)

        // 현재 설정 로드
        EntryPointFinder.customAnnotations.forEach { annotationModel.addElement(it) }
        EntryPointFinder.customSuperClasses.forEach { superClassModel.addElement(it) }

        return root
    }

    override fun isModified(): Boolean {
        val currentAnns = EntryPointFinder.customAnnotations.toSet()
        val currentSups = EntryPointFinder.customSuperClasses.toSet()
        val panelAnns = (0 until annotationModel.size).map { annotationModel[it] }.toSet()
        val panelSups = (0 until superClassModel.size).map { superClassModel[it] }.toSet()
        return currentAnns != panelAnns || currentSups != panelSups
    }

    override fun apply() {
        EntryPointFinder.customAnnotations.clear()
        (0 until annotationModel.size).forEach { EntryPointFinder.customAnnotations.add(annotationModel[it]) }

        EntryPointFinder.customSuperClasses.clear()
        (0 until superClassModel.size).forEach { EntryPointFinder.customSuperClasses.add(superClassModel[it]) }
    }

    override fun reset() {
        annotationModel.clear()
        superClassModel.clear()
        EntryPointFinder.customAnnotations.forEach { annotationModel.addElement(it) }
        EntryPointFinder.customSuperClasses.forEach { superClassModel.addElement(it) }
    }

    private fun buildInfoPanel(): JPanel {
        val p = JPanel(BorderLayout())
        p.border = TitledBorder("자동 탐지 진입점")
        p.add(JLabel(
            "<html>" +
            "<b>기본 지원:</b> @Controller/@RestController, @JmsListener, @RabbitListener,<br/>" +
            "@KafkaListener, @Scheduled, Spring Batch Tasklet/Reader/Writer/Processor<br/><br/>" +
            "<b>클래스 이름 suffix 자동 탐지:</b> *Handler, *Processor, *Action, *Command, *Worker,<br/>" +
            "*Listener, *Consumer, *Receiver, *Router, *Dispatcher<br/><br/>" +
            "위에서 처리되지 않는 전문통신 프레임워크는 아래에 FQN을 추가하세요." +
            "</html>"
        ), BorderLayout.CENTER)
        return p
    }

    private fun buildListPanel(title: String, model: DefaultListModel<String>, hint: String): JPanel {
        val panel = JPanel(BorderLayout(4, 4))
        panel.border = TitledBorder(title)

        val list = JList(model)
        val scrollPane = JScrollPane(list).apply { preferredSize = Dimension(500, 100) }
        panel.add(scrollPane, BorderLayout.CENTER)

        val inputField = JTextField(hint).apply { foreground = java.awt.Color.GRAY }
        inputField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                if (inputField.text == hint) { inputField.text = ""; inputField.foreground = java.awt.Color.BLACK }
            }
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                if (inputField.text.isBlank()) { inputField.text = hint; inputField.foreground = java.awt.Color.GRAY }
            }
        })

        val btnPanel = JPanel().apply {
            add(JButton("추가").apply {
                addActionListener {
                    val text = inputField.text.trim()
                    if (text.isNotBlank() && text != hint && !model.contains(text)) {
                        model.addElement(text)
                        inputField.text = ""
                    }
                }
            })
            add(JButton("삭제").apply {
                addActionListener {
                    val selected = list.selectedValuesList
                    selected.forEach { model.removeElement(it) }
                }
            })
        }

        val bottom = JPanel(BorderLayout(4, 0))
        bottom.add(inputField, BorderLayout.CENTER)
        bottom.add(btnPanel, BorderLayout.EAST)
        panel.add(bottom, BorderLayout.SOUTH)

        return panel
    }

    override fun disposeUIResources() { panel = null }
}
