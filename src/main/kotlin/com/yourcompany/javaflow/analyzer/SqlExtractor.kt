package com.yourcompany.javaflow.analyzer

import com.intellij.psi.*
import com.intellij.psi.xml.XmlFile
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
 */
object SqlExtractor {

    private val MYBATIS_SQL_ANNOTATIONS = mapOf(
        "org.apache.ibatis.annotations.Select" to "SELECT",
        "org.apache.ibatis.annotations.Insert" to "INSERT",
        "org.apache.ibatis.annotations.Update" to "UPDATE",
        "org.apache.ibatis.annotations.Delete" to "DELETE"
    )

    private val XML_SQL_TAGS = setOf("select", "insert", "update", "delete")

    fun extractSql(method: PsiMethod, psiClass: PsiClass, graph: FlowGraph): FlowNode? {
        // 1) MyBatis 어노테이션
        for ((fqn, sqlType) in MYBATIS_SQL_ANNOTATIONS) {
            val ann = method.getAnnotation(fqn)
            if (ann != null) {
                val sql = extractAnnotationText(ann)
                return createSqlNode(method, psiClass, sqlType, sql, graph)
            }
        }

        // 2) JPA @Query
        val queryAnn = method.getAnnotation("org.springframework.data.jpa.repository.Query")
        if (queryAnn != null) {
            val jpql = extractAnnotationText(queryAnn)
            val type = if (jpql.trim().toUpperCase().startsWith("SELECT")) "SELECT(JPQL)" else "DML(JPQL)"
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
        return value.text
            .trimStart('{').trimEnd('}')
            .split(",")
            .joinToString(" ") { it.trim().trim('"') }
            .replace("\\n", "\n")
            .trim()
    }

    private fun findSqlInXmlMapper(method: PsiMethod, psiClass: PsiClass): Pair<String, String>? {
        val project = psiClass.project
        val scope = GlobalSearchScope.projectScope(project)
        val mapperClassName = psiClass.name ?: return null

        // IntelliJ 2021.1 SDK API: getVirtualFilesByName(project, name, scope)
        val xmlFiles = try {
            FilenameIndex.getVirtualFilesByName(project, "${mapperClassName}.xml", scope)
        } catch (e: NoSuchMethodError) {
            emptyList()
        }

        for (vFile in xmlFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: continue
            if (psiFile !is XmlFile) continue
            val root = psiFile.rootTag ?: continue

            val namespace = root.getAttributeValue("namespace") ?: continue
            if (!namespace.endsWith(psiClass.qualifiedName ?: "")) continue

            for (tagName in XML_SQL_TAGS) {
                for (tag in root.findSubTags(tagName)) {
                    if (tag.getAttributeValue("id") == method.name) {
                        val sqlText = tag.value.text.trim()
                        return Pair(tagName.toUpperCase(), sqlText)
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
