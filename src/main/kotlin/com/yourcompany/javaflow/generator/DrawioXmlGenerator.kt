package com.yourcompany.javaflow.generator

import com.yourcompany.javaflow.model.FlowEdge
import com.yourcompany.javaflow.model.FlowGraph
import com.yourcompany.javaflow.model.FlowNode
import com.yourcompany.javaflow.model.NodeType
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.io.StringWriter

/**
 * FlowGraph → draw.io XML (mxGraphModel) 변환기
 *
 * 레이어별 수직 swimlane 레이아웃:
 *  Controller(파랑) → Service(초록) → Repository(노랑) → SQL(빨강)
 */
object DrawioXmlGenerator {

    // 레이어별 X 오프셋 (수평 swimlane)
    private const val LANE_WIDTH = 300
    private const val NODE_HEIGHT = 60
    private const val NODE_HEIGHT_SQL = 80
    private const val NODE_MARGIN = 20
    private const val VERTICAL_SPACING = 100  // 노드 간 수직 간격 (기존 80에서 증가)
    private const val START_X = 40
    private const val START_Y = 80

    // 노드 타입별 스타일
    private val NODE_STYLES = mapOf(
        NodeType.ENTRY_POINT to "rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontStyle=1;fontSize=11;",
        NodeType.WEB_SERVICE to "rounded=1;whiteSpace=wrap;html=1;fillColor=#b0e0e6;strokeColor=#6c8ebf;fontSize=11;",
        NodeType.SERVICE     to "rounded=1;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=11;",
        NodeType.REPOSITORY  to "rounded=1;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;fontSize=11;",
        NodeType.MAPSTRUCT   to "rounded=1;whiteSpace=wrap;html=1;fillColor=#e1d5e7;strokeColor=#9673a6;fontSize=11;",
        NodeType.MODEL       to "rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe0b2;strokeColor=#ef6c00;fontSize=11;",
        NodeType.SQL         to "shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#f8cecc;strokeColor=#b85450;fontSize=10;",
        NodeType.RESPONSE    to "rounded=1;whiteSpace=wrap;html=1;fillColor=#e0f2f1;strokeColor=#00695c;fontStyle=1;fontSize=11;",
        NodeType.UNKNOWN     to "rounded=1;whiteSpace=wrap;html=1;fillColor=#f5f5f5;strokeColor=#666666;fontSize=11;"
    )

    // 레이어 순서 (X축 위치 결정)
    private val LAYER_ORDER = listOf(
        NodeType.ENTRY_POINT,
        NodeType.WEB_SERVICE,
        NodeType.SERVICE,
        NodeType.REPOSITORY,
        NodeType.MAPSTRUCT,
        NodeType.MODEL,
        NodeType.SQL,
        NodeType.RESPONSE,
        NodeType.UNKNOWN
    )

    fun generate(graph: FlowGraph): String {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.newDocument()

        // <mxGraphModel>
        val graphModel = doc.createElement("mxGraphModel").apply {
            setAttribute("dx", "1422")
            setAttribute("dy", "762")
            setAttribute("grid", "1")
            setAttribute("gridSize", "10")
            setAttribute("guides", "1")
            setAttribute("tooltips", "1")
            setAttribute("connect", "1")
            setAttribute("arrows", "1")
            setAttribute("fold", "1")
            setAttribute("page", "1")
            setAttribute("pageScale", "1")
            setAttribute("pageWidth", "1654")
            setAttribute("pageHeight", "1169")
            setAttribute("math", "0")
            setAttribute("shadow", "0")
        }
        doc.appendChild(graphModel)

        val root = doc.createElement("root")
        graphModel.appendChild(root)

        // 기본 셀 2개 (draw.io 필수)
        root.appendChild(createMxCell(doc, "0", null, null))
        root.appendChild(createMxCell(doc, "1", "0", null))

        // 레이어 제목 swimlane 배경 추가
        addSwimlaneTitles(doc, root, graph)

        // 노드를 레이어별로 그룹화하고 Y 위치 계산
        val nodePositions = calculateNodePositions(graph.nodes)

        // 노드 셀 추가
        for (node in graph.nodes) {
            val (x, y) = nodePositions[node.id] ?: Pair(START_X, START_Y)
            val style = NODE_STYLES[node.type] ?: NODE_STYLES[NodeType.UNKNOWN]!!
            val labelHtml = buildNodeLabel(node)

            val cell = doc.createElement("mxCell").apply {
                setAttribute("id", sanitizeId(node.id))
                setAttribute("value", labelHtml)
                setAttribute("style", style)
                setAttribute("vertex", "1")
                setAttribute("parent", "1")
            }
            val geo = doc.createElement("mxGeometry").apply {
                setAttribute("x", x.toString())
                setAttribute("y", y.toString())
                setAttribute("width", (LANE_WIDTH - NODE_MARGIN * 2).toString())
                val height = if (node.type == NodeType.SQL && node.detail.length > 50) NODE_HEIGHT_SQL else NODE_HEIGHT
                setAttribute("height", height.toString())
                setAttribute("as", "geometry")
            }
            cell.appendChild(geo)
            root.appendChild(cell)
        }

        // 엣지 셀 추가
        for (edge in graph.edges) {
            val fromNode = graph.getNode(edge.fromId)
            val toNode = graph.getNode(edge.toId)
            val edgeStyle = calculateEdgeStyle(fromNode, toNode)

            val cell = doc.createElement("mxCell").apply {
                setAttribute("id", sanitizeId(edge.id))
                setAttribute("value", edge.label)
                setAttribute("style", edgeStyle)
                setAttribute("edge", "1")
                setAttribute("source", sanitizeId(edge.fromId))
                setAttribute("target", sanitizeId(edge.toId))
                setAttribute("parent", "1")
            }
            val geo = doc.createElement("mxGeometry").apply {
                setAttribute("relative", "1")
                setAttribute("as", "geometry")
            }
            cell.appendChild(geo)
            root.appendChild(cell)
        }

        // XML 직렬화 (비압축, 사람이 읽을 수 있는 형식)
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, "") // XML 선언 suppress
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }

    private fun buildNodeLabel(node: FlowNode): String {
        val mainLabel = escapeXml(node.label)
        return if (node.detail.isNotBlank()) {
            val detail = escapeXml(node.detail)
            "<b>$mainLabel</b><br/><font style=\"font-size:9px;color:#555555;\">$detail</font>"
        } else {
            "<b>$mainLabel</b>"
        }
    }

    private fun calculateNodePositions(nodes: Collection<FlowNode>): Map<String, Pair<Int, Int>> {
        val positions = mutableMapOf<String, Pair<Int, Int>>()
        // 레이어별 카운터
        val layerCounters = mutableMapOf<NodeType, Int>()

        for (node in nodes) {
            val layerIndex = LAYER_ORDER.indexOf(node.type).coerceAtLeast(0)
            val count = layerCounters.getOrDefault(node.type, 0)
            layerCounters[node.type] = count + 1

            val x = START_X + layerIndex * LANE_WIDTH
            val y = START_Y + count * VERTICAL_SPACING
            positions[node.id] = Pair(x, y)
        }
        return positions
    }

    private fun calculateEdgeStyle(fromNode: FlowNode?, toNode: FlowNode?): String {
        if (fromNode == null || toNode == null) {
            return "edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0.5;exitY=1;exitDx=0;exitDy=0;entryX=0.5;entryY=0;entryDx=0;entryDy=0;fontSize=10;"
        }

        val fromLayerIndex = LAYER_ORDER.indexOf(fromNode.type).coerceAtLeast(0)
        val toLayerIndex = LAYER_ORDER.indexOf(toNode.type).coerceAtLeast(0)

        // 같은 레이어 내 또는 수직 이동: 위→아래
        // 다른 레이어 간 이동: 왼쪽→오른쪽
        return if (fromLayerIndex == toLayerIndex) {
            // 같은 레이어: 수직 연결 (위→아래)
            "edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0.5;exitY=1;exitDx=0;exitDy=0;entryX=0.5;entryY=0;entryDx=0;entryDy=0;fontSize=10;"
        } else {
            // 다른 레이어: 수평 연결 (왼쪽→오른쪽)
            "edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.5;exitDx=0;exitDy=0;entryX=0;entryY=0.5;entryDx=0;entryDy=0;fontSize=10;"
        }
    }

    private fun addSwimlaneTitles(doc: org.w3c.dom.Document, root: Element, graph: FlowGraph) {
        val usedTypes = graph.nodes.map { it.type }.toSet()
        val typeLabels = mapOf(
            NodeType.ENTRY_POINT to "Controller (진입점)",
            NodeType.WEB_SERVICE to "Web Service (API 레이어)",
            NodeType.SERVICE     to "Domain Service (비즈니스)",
            NodeType.REPOSITORY  to "Repository / MyBatis",
            NodeType.MAPSTRUCT   to "MapStruct Mapper",
            NodeType.MODEL       to "Model / DTO (변환 결과)",
            NodeType.SQL         to "SQL / DB",
            NodeType.RESPONSE    to "Response (응답 타입)",
            NodeType.UNKNOWN     to "기타 / 결과 없음"
        )
        val typeFills = mapOf(
            NodeType.ENTRY_POINT to "#dae8fc",
            NodeType.WEB_SERVICE to "#b0e0e6",
            NodeType.SERVICE     to "#d5e8d4",
            NodeType.REPOSITORY  to "#fff2cc",
            NodeType.MAPSTRUCT   to "#e1d5e7",
            NodeType.MODEL       to "#ffe0b2",
            NodeType.SQL         to "#f8cecc",
            NodeType.RESPONSE    to "#e0f2f1",
            NodeType.UNKNOWN     to "#f5f5f5"
        )

        for ((index, nodeType) in LAYER_ORDER.withIndex()) {
            if (nodeType !in usedTypes) continue
            val x = START_X + index * LANE_WIDTH
            val fill = typeFills[nodeType] ?: "#f5f5f5"
            val label = typeLabels[nodeType] ?: "기타"

            val cell = doc.createElement("mxCell").apply {
                setAttribute("id", "header_$index")
                setAttribute("value", "<b>$label</b>")
                setAttribute("style", "text;html=1;align=center;verticalAlign=middle;resizable=0;points=[];autosize=1;strokeColor=none;fillColor=$fill;fontSize=12;")
                setAttribute("vertex", "1")
                setAttribute("parent", "1")
            }
            val geo = doc.createElement("mxGeometry").apply {
                setAttribute("x", x.toString())
                setAttribute("y", "20")
                setAttribute("width", (LANE_WIDTH - NODE_MARGIN).toString())
                setAttribute("height", "40")
                setAttribute("as", "geometry")
            }
            cell.appendChild(geo)
            root.appendChild(cell)
        }
    }

    private fun createMxCell(
        doc: org.w3c.dom.Document,
        id: String,
        parent: String?,
        value: String?
    ): Element {
        return doc.createElement("mxCell").apply {
            setAttribute("id", id)
            if (parent != null) setAttribute("parent", parent)
            if (value != null) setAttribute("value", value)
        }
    }

    private fun sanitizeId(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
