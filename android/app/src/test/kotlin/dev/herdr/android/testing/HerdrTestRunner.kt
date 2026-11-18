package dev.herdr.android.testing

import java.lang.reflect.Method
import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner

/**
 * The Robolectric runner for this app's tests: the same, except that each test
 * ends with the main thread's due work run ([MainThreadDrain]), so no test can
 * leave Compose waiting on a callback the next test never gets.
 */
class HerdrTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun afterTest(method: FrameworkMethod, bootstrappedMethod: Method) {
        try {
            // Loaded through the sandbox's class loader, so it sees the sandbox's main looper.
            Class.forName(MainThreadDrain::class.java.name, true, bootstrappedMethod.declaringClass.classLoader)
                .getMethod("run")
                .invoke(null)
        } finally {
            super.afterTest(method, bootstrappedMethod)
        }
    }
}
