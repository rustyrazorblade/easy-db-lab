package com.rustyrazorblade.easydblab.docker

import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.StreamType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.CompositeOutputHandler
import com.rustyrazorblade.easydblab.output.ConsoleOutputHandler
import com.rustyrazorblade.easydblab.output.LoggerOutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class OutputHandlerTest {
    @Test
    fun `BufferedOutputHandler captures stdout correctly`() {
        val handler = BufferedOutputHandler()
        val frame = Frame(StreamType.STDOUT, "Hello World\n".toByteArray())

        handler.handleFrame(frame)

        assertThat(handler.stdout).isEqualTo("Hello World\n")
        assertThat(handler.stderr).isEmpty()
    }

    @Test
    fun `BufferedOutputHandler captures stderr correctly`() {
        val handler = BufferedOutputHandler()
        val frame = Frame(StreamType.STDERR, "Error occurred\n".toByteArray())

        handler.handleFrame(frame)

        assertThat(handler.stdout).isEmpty()
        assertThat(handler.stderr).isEqualTo("Error occurred\n")
    }

    @Test
    fun `BufferedOutputHandler captures messages correctly`() {
        val handler = BufferedOutputHandler()

        handler.handleMessage("Status update 1")
        handler.handleMessage("Status update 2")

        assertThat(handler.messages).containsExactly("Status update 1", "Status update 2")
    }

    @Test
    fun `BufferedOutputHandler captures errors correctly`() {
        val handler = BufferedOutputHandler()
        val exception = RuntimeException("Test error")

        handler.handleError("Error occurred", exception)
        handler.handleError("Another error", null)

        assertThat(handler.errors).hasSize(2)
        assertThat(handler.errors[0]).isEqualTo("Error occurred" to exception)
        assertThat(handler.errors[1]).isEqualTo("Another error" to null)
    }

    @Test
    fun `BufferedOutputHandler clear works correctly`() {
        val handler = BufferedOutputHandler()
        handler.handleFrame(Frame(StreamType.STDOUT, "data".toByteArray()))
        handler.handleMessage("message")
        handler.handleError("error", null)

        handler.clear()

        assertThat(handler.stdout).isEmpty()
        assertThat(handler.stderr).isEmpty()
        assertThat(handler.messages).isEmpty()
        assertThat(handler.errors).isEmpty()
    }

    @Test
    fun `ConsoleOutputHandler writes to stdout and stderr`() {
        val handler = ConsoleOutputHandler()

        // Capture stdout and stderr
        val originalOut = System.out
        val originalErr = System.err
        val capturedOut = ByteArrayOutputStream()
        val capturedErr = ByteArrayOutputStream()

        try {
            System.setOut(PrintStream(capturedOut))
            System.setErr(PrintStream(capturedErr))

            handler.handleFrame(Frame(StreamType.STDOUT, "stdout text".toByteArray()))
            handler.handleFrame(Frame(StreamType.STDERR, "stderr text".toByteArray()))
            handler.handleMessage("message text")
            handler.handleError("error text", null)

            // Flush the streams to ensure all data is written
            System.out.flush()
            System.err.flush()

            val outContent = capturedOut.toString()
            val errContent = capturedErr.toString()

            assertThat(outContent).isEqualTo("stdout textmessage text\n")
            assertThat(errContent).isEqualTo("stderr texterror text\n")
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    @Test
    fun `CompositeOutputHandler delegates to all handlers`() {
        val handler1 = BufferedOutputHandler()
        val handler2 = BufferedOutputHandler()
        val composite = CompositeOutputHandler(handler1, handler2)

        val frame = Frame(StreamType.STDOUT, "test data".toByteArray())
        composite.handleFrame(frame)
        composite.handleMessage("test message")
        composite.handleError("test error", null)

        // Both handlers should receive the same data
        assertThat(handler1.stdout).isEqualTo("test data")
        assertThat(handler2.stdout).isEqualTo("test data")
        assertThat(handler1.messages).containsExactly("test message")
        assertThat(handler2.messages).containsExactly("test message")
        assertThat(handler1.errors).hasSize(1)
        assertThat(handler2.errors).hasSize(1)
    }

    @Test
    fun `CompositeOutputHandler can be created empty and handlers added dynamically`() {
        val composite = CompositeOutputHandler()
        assertThat(composite.getHandlerCount()).isZero()

        val handler1 = BufferedOutputHandler()
        val handler2 = BufferedOutputHandler()

        composite.addHandler(handler1)
        assertThat(composite.getHandlerCount()).isEqualTo(1)
        assertThat(composite.hasHandler(handler1)).isTrue()

        composite.addHandler(handler2)
        assertThat(composite.getHandlerCount()).isEqualTo(2)
        assertThat(composite.hasHandler(handler2)).isTrue()

        // Test output goes to both handlers
        composite.handleMessage("dynamic test")
        assertThat(handler1.messages).containsExactly("dynamic test")
        assertThat(handler2.messages).containsExactly("dynamic test")
    }

    @Test
    fun `CompositeOutputHandler can remove handlers dynamically`() {
        val handler1 = BufferedOutputHandler()
        val handler2 = BufferedOutputHandler()
        val composite = CompositeOutputHandler(handler1, handler2)

        assertThat(composite.getHandlerCount()).isEqualTo(2)

        // Remove handler1
        assertThat(composite.removeHandler(handler1)).isTrue()
        assertThat(composite.getHandlerCount()).isEqualTo(1)
        assertThat(composite.hasHandler(handler1)).isFalse()
        assertThat(composite.hasHandler(handler2)).isTrue()

        // Output should only go to handler2 now
        composite.handleMessage("after removal")
        assertThat(handler1.messages).isEmpty()
        assertThat(handler2.messages).containsExactly("after removal")

        // Try to remove non-existent handler
        assertThat(composite.removeHandler(handler1)).isFalse()
    }

    @Test
    fun `CompositeOutputHandler removeAllHandlers clears all handlers`() {
        val handler1 = BufferedOutputHandler()
        val handler2 = BufferedOutputHandler()
        val composite = CompositeOutputHandler(handler1, handler2)

        assertThat(composite.getHandlerCount()).isEqualTo(2)

        composite.removeAllHandlers()
        assertThat(composite.getHandlerCount()).isZero()
        assertThat(composite.hasHandler(handler1)).isFalse()
        assertThat(composite.hasHandler(handler2)).isFalse()

        // No output should be processed
        composite.handleMessage("after clear")
        assertThat(handler1.messages).isEmpty()
        assertThat(handler2.messages).isEmpty()
    }

    @Test
    fun `CompositeOutputHandler prevents duplicate handlers`() {
        val handler = BufferedOutputHandler()
        val composite = CompositeOutputHandler()

        composite.addHandler(handler)
        assertThat(composite.getHandlerCount()).isEqualTo(1)

        // Adding the same handler again should throw
        assertThatThrownBy { composite.addHandler(handler) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Handler already exists in composite")
        assertThat(composite.getHandlerCount()).isEqualTo(1)
    }

    @Test
    fun `CompositeOutputHandler getHandlers returns defensive copy`() {
        val handler1 = BufferedOutputHandler()
        val handler2 = BufferedOutputHandler()
        val composite = CompositeOutputHandler(handler1, handler2)

        val handlersList = composite.getHandlers()
        assertThat(handlersList).hasSize(2)
        assertThat(handlersList).contains(handler1, handler2)

        // Modifying the returned list should not affect the composite
        val mutableList = handlersList as? MutableList
        if (mutableList != null) {
            mutableList.clear()
        }
        assertThat(composite.getHandlerCount()).isEqualTo(2) // Should still be 2
    }

    @Test
    fun `CompositeOutputHandler handles empty handler list gracefully`() {
        val composite = CompositeOutputHandler()
        assertThat(composite.getHandlerCount()).isZero()

        // These operations should not throw exceptions
        composite.handleFrame(Frame(StreamType.STDOUT, "test".toByteArray()))
        composite.handleMessage("test message")
        composite.handleError("test error", null)
        composite.close()
    }

    @Test
    fun `LoggerOutputHandler processes frames correctly`() {
        val handler = LoggerOutputHandler("TestLogger")

        // Just ensure it doesn't throw exceptions
        handler.handleFrame(Frame(StreamType.STDOUT, "log line\n".toByteArray()))
        handler.handleFrame(Frame(StreamType.STDERR, "error line\n".toByteArray()))
        handler.handleMessage("status message")
        handler.handleError("error message", RuntimeException("test"))
        handler.close()
    }
}
