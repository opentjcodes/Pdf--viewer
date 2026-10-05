package com.gitlab.mudlej.MjPdfReader.suite.core

import org.junit.runner.Description

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class TestId(val value: String)

object TestIds {

    private val allowedCharacters = Regex("[^A-Za-z0-9._-]")

    fun of(description: Description): String {
        val declared = description.getAnnotation(TestId::class.java)?.value
            ?: description.testClass?.getAnnotation(TestId::class.java)?.value
        if (declared != null && declared.isNotBlank()) {
            return sanitize(declared + parameterSuffix(description))
        }
        return sanitize(derivedFrom(description))
    }

    private fun parameterSuffix(description: Description): String {
        val methodName = description.methodName ?: return ""
        val open = methodName.indexOf('[')
        if (open < 0 || !methodName.endsWith("]")) return ""
        return methodName.substring(open)
    }

    private fun derivedFrom(description: Description): String {
        val className = description.className.substringAfterLast('.')
        val methodName = description.methodName ?: "class"
        return "$className.$methodName"
    }

    private fun sanitize(value: String) = allowedCharacters.replace(value, "_")
}
