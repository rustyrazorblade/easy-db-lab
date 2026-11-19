package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.services.SparkJobState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import software.amazon.awssdk.services.emr.model.StepState

/**
 * The EMR step state a user sees is the project's own [SparkJobState]. Every state this SDK
 * version knows maps to its namesake, and one it does not know (or none at all) is UNKNOWN,
 * never a literal "null".
 */
class SparkJobStateMappingTest {
    @ParameterizedTest
    @EnumSource(StepState::class, names = ["UNKNOWN_TO_SDK_VERSION"], mode = EnumSource.Mode.EXCLUDE)
    fun `every known EMR state maps to the state of the same name`(state: StepState) {
        assertThat(state.toSparkJobState().name).isEqualTo(state.name)
    }

    @Test
    fun `a state this SDK version does not know is UNKNOWN`() {
        assertThat(StepState.UNKNOWN_TO_SDK_VERSION.toSparkJobState()).isEqualTo(SparkJobState.UNKNOWN)
    }

    @Test
    fun `a missing state is UNKNOWN`() {
        val missing: StepState? = null

        assertThat(missing.toSparkJobState()).isEqualTo(SparkJobState.UNKNOWN)
    }
}
