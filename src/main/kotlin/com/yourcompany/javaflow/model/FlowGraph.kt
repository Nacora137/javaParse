package com.yourcompany.javaflow.model

/**
 * 분석된 흐름 전체를 담는 컨테이너
 */
data class FlowGraph(
    val title: String,           // 예: "GET /api/members/list"
    private val nodesMap: MutableMap<String, FlowNode> = mutableMapOf(),
    val edges: MutableList<FlowEdge> = mutableListOf()
) {
    val nodes: Collection<FlowNode> get() = nodesMap.values

    fun addNode(node: FlowNode) {
        nodesMap[node.id] = node
    }

    fun addEdge(edge: FlowEdge) = edges.add(edge)
    fun hasNode(id: String) = nodesMap.containsKey(id)
    fun getNode(id: String) = nodesMap[id]
}
