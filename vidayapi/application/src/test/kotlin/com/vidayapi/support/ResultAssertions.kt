package com.vidayapi.support

import com.vidayapi.model.Error
import com.vidayapi.model.Result
import org.assertj.core.api.Assertions.assertThat

fun <T> Result<T>.assertRight(): T {
    assertThat(isSuccess).describedAs("expected success, but was failure=${exceptionOrNull()}").isTrue()
    return getOrThrow()
}

fun <T> Result<T>.assertLeft(): Error {
    val error = exceptionOrNull()
    assertThat(error).describedAs("expected failure, but was success=${getOrNull()}").isNotNull()
    return error as Error
}

inline fun <reified E : Error> Result<*>.assertLeftType(): E {
    val error = assertLeft()
    assertThat(error).isInstanceOf(E::class.java)
    return error as E
}
