package com.yourcompany.javaflow.analyzer

import com.intellij.psi.*
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.yourcompany.javaflow.model.FlowGraph
import com.yourcompany.javaflow.model.FlowNode
import com.yourcompany.javaflow.model.NodeType

/**
 * SQL 쿼리 추출기
 *
 * 지원:
 *  1) MyBatis 어노테이션: @Select, @Insert, @Update, @Delete
 *  2) MyBatis XML Mapper: *Mapper.xml <select>, <insert>, <update>, <delete>
 *  3) JPA @Query 어노테이션
 *  4) @NamedQuery (레거시 JPA)
 */
object SqlExtractor {

    private val MYBATIS_SQL_ANNOTATIONS = mapOf(
        "org.apache.ibatis.annotations.Select" to "SELECT",
        "org.apache.ibatis.annotations.Insert" to "INSERT",
        "org.apache.ibatis.annotations.Update" to "UPDATE",
        "org.apache.ibatis.annotations.Delete" to "DELETE"
    )

    private val XML_SQL_TAGS = setOf("select", "insert", "update", "delete")

    /**
     * Mapper/DAO 메서드에서 SQL을 추출해 FlowNode로 반환합니다.
     * SQL을 찾지 못하면 null 반환.
     */
    fun extractSql(method: PsiMethod, psiClass: PsiClass, graph: FlowGraph): FlowNode? {
        // 1) MyBatis 어노테이션 체크
        for ((fqn, sqlType) in MYBATIS_SQL_ANNOTATIONS) {
            val ann = method.getAnnotation(fqn)
            if (ann != null) {
                val sql = extractAnnotationText(ann)
                return createSqlNode(method, psiClass, sqlType, sql, graph)
            }
        }

        // 2) JPA @Query 체크
        val queryAnn = method.getAnnotation("org.springframework.data.jpa.repository.Query")
        if (queryAnn != null) {
            val jpql = extractAnnotationText(queryAnn)
            val type = if (jpql.trim().uppercase().startsWith("SELECT")) "SELECT(JPQL)" else "DML(JPQL)"
            return createSqlNode(method, psiClass, type, jpql, graph)
        }

        // 3) MyBatis XML Mapper 탐색
        val xmlSql = findSqlInXmlMapper(method, psiClass)
        if (xmlSql != null) {
            return createSqlNode(method, psiClass, xmlSql.first, xmlSql.second, graph)
        }

        return null
    }

    private fun extractAnnotationText(ann: PsiAnnotation): String {
        val value = ann.findAttributeValue("value") ?: return ""
        // 배열 형태 {"line1", "line2"} 처리
        return value.text
            .trimStart('{').trimEnd('}')
            .split(",")
            .joinToString(" ") { it.trim().trim('"') }
            .replace("\\n", "\n")
            .trim()
    }

    /**
     * MyBatis XML mapper 파일에서 메서드에 대응하는 SQL 블록을 탐색합니다.
     * mapper namespace = 클래스 FQN, id = 메서드명
     */
    private fun findSqlInXmlMapper(method: PsiMethod, psiClass: PsiClass): Pair<String, String>? {
        val project = psiClass.project
        val scope = GlobalSearchScope.projectScope(project)
        val mapperClassName = psiClass.name ?: return null

        // *Mapper.xml 파일 탐색
        val xmlFiles = FilenameIndex.getVirtualFilesByName(
            "${mapperClassName}.xml", scope
        )

        for (vFile in xmlFiles) {
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vFile)
            if (psiFile !is XmlFile) continue
            val root = psiFile.rootTag ?: continue

            // namespace 확인
            val namespace = root.getAttributeValue("namespace") ?: continue
            if (!namespace.endsWith(psiClass.qualifiedName ?: "")) continue

            // id = method.name인 SQL 태그 탐색
            for (tagName in XML_SQL_TAGS) {
                root.findSubTags(tagName).forEach { tag ->
                    if (tag.getAttributeValue("id") == method.name) {
                        val sqlText = tag.value.text.trim()
                        return Pair(tagName.uppercase(), sqlText)
                    }
                }
            }
        }
        return null
    }

    private fun createSqlNode(
        method: PsiMethod,
        psiClass: PsiClass,
        sqlType: String,
        sql: String,
        graph: FlowGraph
    ): FlowNode {
        val nodeId = "sql_${psiClass.qualifiedName}_${method.name}"
        // SQL이 길면 줄여서 표시
        val displaySql = if (sql.length > 120) sql.substring(0, 117) + "..." else sql
        val node = FlowNode(
            id = nodeId,
            label = "[$sqlType] ${method.name}",
            type = NodeType.SQL,
            detail = displaySql,
            className = psiClass.qualifiedName ?: "",
            methodName = method.name
        )
        if (!graph.hasNode(nodeId)) graph.addNode(node)
        return node
    }
}
