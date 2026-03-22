package com.yourcompany.javaflow.model

/**
 * 분석된 흐름 전체를 담는 컨테이너
 */
data class FlowGraph(
    val title: String,           // 예: "GET /api/members/list"
    val nodes: MutableList<FlowNode> = mutableListOf(),
    val edges: MutableList<FlowEdge> = mutableListOf()
) {
    fun addNode(node: FlowNode) = nodes.add(node)
    fun addEdge(edge: FlowEdge) = edges.add(edge)
    fun hasNode(id: String) = nodes.any { it.id == id }
}
