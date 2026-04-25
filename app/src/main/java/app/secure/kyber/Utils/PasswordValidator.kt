package app.secure.kyber.utils

enum class PasswordStrength {
    WEAK,
    MEDIUM,
    STRONG
}

data class PasswordValidationResult(
    val hasMinLength: Boolean,
    val hasUpperAndLowerCase: Boolean,
    val hasDigit: Boolean,
    val hasSpecialChar: Boolean,
    val strength: PasswordStrength
)

object PasswordValidator {

    fun validatePassword(password: String): PasswordValidationResult {
        val hasMinLength = password.length >= 8
        val hasUpperCase = password.any { it.isUpperCase() }
        val hasLowerCase = password.any { it.isLowerCase() }
        val hasUpperAndLowerCase = hasUpperCase && hasLowerCase
        val hasDigit = password.any { it.isDigit() }
        val hasSpecialChar = password.any { !it.isLetterOrDigit() }

        val conditionsMet = listOf(hasMinLength, hasUpperAndLowerCase, hasDigit, hasSpecialChar).count { it }

        val strength = when {
            conditionsMet == 4 -> PasswordStrength.STRONG
            conditionsMet >= 2 -> PasswordStrength.MEDIUM
            else -> PasswordStrength.WEAK
        }

        return PasswordValidationResult(
            hasMinLength = hasMinLength,
            hasUpperAndLowerCase = hasUpperAndLowerCase,
            hasDigit = hasDigit,
            hasSpecialChar = hasSpecialChar,
            strength = strength
        )
    }
}
