package com.yourcompany.javaflow.analyzer

import com.intellij.psi.*
import com.yourcompany.javaflow.model.FlowGraph
import com.yourcompany.javaflow.model.FlowNode
import com.yourcompany.javaflow.model.NodeType

/**
 * DB 접근 레이어 및 도메인 매퍼 탐색기
 * (MyBatis, JPA, Legacy DAO, MapStruct 지원)
 */
object DbAccessDetector {

    private val JPA_SUPER_INTERFACES = setOf(
        "org.springframework.data.repository.Repository",
        "org.springframework.data.jpa.repository.JpaRepository",
        "org.springframework.data.repository.CrudRepository",
        "org.springframework.data.repository.PagingAndSortingRepository"
    )

    private val MYBATIS_ANNOTATIONS = setOf(
        "org.apache.ibatis.annotations.Mapper",
        "org.mybatis.spring.annotation.MapperScan"
    )

    private val JDBC_CLASSES = setOf(
        "org.springframework.jdbc.core.JdbcTemplate",
        "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
        "java.sql.Connection",
        "java.sql.PreparedStatement"
    )

    private const val MAPSTRUCT_MAPPER = "org.mapstruct.Mapper"

    fun isMapStruct(psiClass: PsiClass): Boolean {
        return psiClass.annotations.any { it.qualifiedName == MAPSTRUCT_MAPPER }
    }

    fun isDbAccessClass(psiClass: PsiClass): Boolean {
        val name = psiClass.name ?: return false

        // 1) JPA Repository 인터페이스 상속 체크
        if (extendsJpaRepository(psiClass)) return true

        // 2) @Mapper 어노테이션 체크 (MyBatis: org.apache.ibatis.annotations.Mapper)
        if (psiClass.annotations.any { ann -> MYBATIS_ANNOTATIONS.any { it == ann.qualifiedName } }) return true

        // 3) 클래스명 패턴 체크 (레거시 / MyBatis 관례)
        // 단, MapStruct는 제외 (별도로 처리)
        if (!isMapStruct(psiClass) &&
            (name.endsWith("Mapper") || name.endsWith("DAO") ||
             name.endsWith("Dao") || name.endsWith("DaoImpl") ||
             name.endsWith("Repository"))
        ) return true

        // 4) @Repository 어노테이션 체크
        if (psiClass.annotations.any { it.qualifiedName == "org.springframework.stereotype.Repository" }) return true

        return false
    }

    private fun extendsJpaRepository(psiClass: PsiClass): Boolean {
        fun checkSuperInterfaces(cls: PsiClass, visited: MutableSet<String>): Boolean {
            val fqn = cls.qualifiedName ?: return false
            if (fqn in visited) return false
            visited.add(fqn)
            if (fqn in JPA_SUPER_INTERFACES) return true
            return cls.interfaces.any { checkSuperInterfaces(it, visited) }
        }
        return checkSuperInterfaces(psiClass, mutableSetOf())
    }

    // DB 접근 메서드를 FlowNode로 변환합니다.
    fun buildDbNode(method: PsiMethod, psiClass: PsiClass, graph: FlowGraph): FlowNode {
        val nodeId = "db_${psiClass.qualifiedName}_${method.name}"
        val label = "${psiClass.name}.${method.name}()"
        val dbType = detectDbType(psiClass)

        val node = FlowNode(
            id = nodeId,
            label = label,
            type = NodeType.REPOSITORY,
            detail = dbType,
            className = psiClass.qualifiedName ?: "",
            methodName = method.name
        )
        graph.addNode(node)
        return node
    }

    private fun detectDbType(psiClass: PsiClass): String {
        return when {
            isMapStruct(psiClass) -> "MapStruct"
            extendsJpaRepository(psiClass) -> "JPA Repository"
            psiClass.annotations.any { ann -> MYBATIS_ANNOTATIONS.any { it == ann.qualifiedName } } -> "MyBatis Mapper"
            psiClass.name?.endsWith("Mapper") == true -> "MyBatis Mapper"
            else -> "DAO / JDBC"
        }
    }

    fun determineNodeType(psiClass: PsiClass): NodeType {
        return when {
            isMapStruct(psiClass) -> NodeType.MAPSTRUCT
            isDbAccessClass(psiClass) -> NodeType.REPOSITORY
            else -> NodeType.UNKNOWN
        }
    }
}
