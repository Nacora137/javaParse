package com.yourcompany.javaflow.model

/**
 * 흐름 다이어그램 엣지 (노드 간 연결 화살표)
 */
data class FlowEdge(
    val id: String,
    val fromId: String,
    val toId: String,
    val label: String = ""       // 연결 설명 (예: "calls", "returns")
)
