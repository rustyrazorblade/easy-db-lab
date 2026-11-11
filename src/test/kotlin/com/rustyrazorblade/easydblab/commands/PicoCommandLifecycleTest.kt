package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.PostExecute
import com.rustyrazorblade.easydblab.annotations.PreExecute
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PicoCommandLifecycleTest {
    /**
     * Test command that tracks lifecycle method execution order.
     */
    class LifecycleTrackingCommand : PicoCommand {
        val executionOrder = mutableListOf<String>()

        @Suppress("UnusedPrivateMember") // Called via reflection
        @PreExecute
        private fun preExecuteA() {
            executionOrder.add("preA")
        }

        @Suppress("UnusedPrivateMember") // Called via reflection
        @PreExecute
        private fun preExecuteB() {
            executionOrder.add("preB")
        }

        override fun execute() {
            executionOrder.add("execute")
        }

        @Suppress("UnusedPrivateMember") // Called via reflection
        @PostExecute
        private fun postExecuteA() {
            executionOrder.add("postA")
        }

        @Suppress("UnusedPrivateMember") // Called via reflection
        @PostExecute
        private fun postExecuteB() {
            executionOrder.add("postB")
        }
    }

    /**
     * Test command with no lifecycle annotations.
     */
    class SimpleCommand : PicoCommand {
        var executed = false

        override fun execute() {
            executed = true
        }
    }

    /**
     * Test command where PreExecute throws an exception.
     */
    class PreExecuteThrowsCommand : PicoCommand {
        val executionOrder = mutableListOf<String>()

        @Suppress("UnusedPrivateMember") // Called via reflection
        @PreExecute
        private fun preExecute() {
            executionOrder.add("pre")
            error("PreExecute failed")
        }

        override fun execute() {
            executionOrder.add("execute")
        }

        @Suppress("UnusedPrivateMember") // Called via reflection
        @PostExecute
        private fun postExecute() {
            executionOrder.add("post")
        }
    }

    /**
     * Test command where execute throws an exception.
     */
    class ExecuteThrowsCommand : PicoCommand {
        val executionOrder = mutableListOf<String>()

        @Suppress("UnusedPrivateMember") // Called via reflection
        @PreExecute
        private fun preExecute() {
            executionOrder.add("pre")
        }

        override fun execute() {
            executionOrder.add("execute")
            error("Execute failed")
        }

        @Suppress("UnusedPrivateMember") // Called via reflection
        @PostExecute
        private fun postExecute() {
            executionOrder.add("post")
        }
    }

    @Test
    fun `executes PreExecute methods before execute`() {
        val command = LifecycleTrackingCommand()
        command.call()

        assertThat(command.executionOrder.indexOf("preA"))
            .isLessThan(command.executionOrder.indexOf("execute"))
        assertThat(command.executionOrder.indexOf("preB"))
            .isLessThan(command.executionOrder.indexOf("execute"))
    }

    @Test
    fun `executes PostExecute methods after execute`() {
        val command = LifecycleTrackingCommand()
        command.call()

        assertThat(command.executionOrder.indexOf("postA"))
            .isGreaterThan(command.executionOrder.indexOf("execute"))
        assertThat(command.executionOrder.indexOf("postB"))
            .isGreaterThan(command.executionOrder.indexOf("execute"))
    }

    @Test
    fun `executes PreExecute methods in alphabetical order by method name`() {
        val command = LifecycleTrackingCommand()
        command.call()

        val preAIndex = command.executionOrder.indexOf("preA")
        val preBIndex = command.executionOrder.indexOf("preB")
        assertThat(preAIndex).isLessThan(preBIndex)
    }

    @Test
    fun `executes PostExecute methods in alphabetical order by method name`() {
        val command = LifecycleTrackingCommand()
        command.call()

        val postAIndex = command.executionOrder.indexOf("postA")
        val postBIndex = command.executionOrder.indexOf("postB")
        assertThat(postAIndex).isLessThan(postBIndex)
    }

    @Test
    fun `returns 0 on successful execution`() {
        val command = SimpleCommand()
        val result = command.call()

        assertThat(result).isEqualTo(0)
        assertThat(command.executed).isTrue()
    }

    @Test
    fun `works correctly with no lifecycle annotations`() {
        val command = SimpleCommand()
        command.call()

        assertThat(command.executed).isTrue()
    }

    @Test
    fun `propagates exception from PreExecute method`() {
        val command = PreExecuteThrowsCommand()

        assertThatThrownBy { command.call() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("PreExecute failed")

        // Verify execute was not called
        assertThat(command.executionOrder).containsExactly("pre")
    }

    @Test
    fun `propagates exception from execute method`() {
        val command = ExecuteThrowsCommand()

        assertThatThrownBy { command.call() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Execute failed")

        // Verify pre was called but post was not
        assertThat(command.executionOrder).containsExactly("pre", "execute")
    }

    @Test
    fun `complete execution order is correct`() {
        val command = LifecycleTrackingCommand()
        command.call()

        assertThat(command.executionOrder).containsExactly(
            "preA",
            "preB",
            "execute",
            "postA",
            "postB",
        )
    }
}
