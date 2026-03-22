package com.yourcompany.javaflow.model

/**
 * 흐름 다이어그램 노드 (Controller, Service, Repository, SQL 등 각 레이어의 단위)
 */
data class FlowNode(
    val id: String,
    val label: String,           // 표시 이름 (예: "MemberController.getList")
    val type: NodeType,
    val detail: String = "",     // 추가 정보 (예: SQL 쿼리, HTTP 메서드+URL)
    val className: String = "",  // 소속 클래스 풀네임
    val methodName: String = ""  // 메서드명
)

enum class NodeType {
    ENTRY_POINT,    // @Controller / @RestController 메서드
    SERVICE,        // @Service 메서드
    REPOSITORY,     // @Repository / JpaRepository / *Mapper / *DAO (MyBatis)
    MAPSTRUCT,      // MapStruct Mapper (@org.mapstruct.Mapper)
    SQL,            // 실제 SQL 쿼리 블록
    UNKNOWN
}
