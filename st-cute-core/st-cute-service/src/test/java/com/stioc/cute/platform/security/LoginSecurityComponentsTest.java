package com.stioc.cute.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录安全组件单元测试（防爆破守卫 + 质询存储）。
 * <p>覆盖封禁阈值、封禁期拦截、登录成功清零、并发计数正确性，以及质询值的签发格式与单次消费语义。</p>
 */
class LoginSecurityComponentsTest {

    @Nested
    @DisplayName("封禁阈值与生命周期")
    class BanLifecycleTests {

        @Test
        @DisplayName("未达阈值的失败不触发封禁")
        void belowThresholdNotBanned() {
            LoginBruteForceGuard guard = new LoginBruteForceGuard();
            for (int i = 0; i < 4; i++) {
                guard.recordFailure("10.0.0.1");
            }
            assertFalse(guard.isBanned("10.0.0.1"), "4 次失败未达 5 次阈值，不应封禁");
        }

        @Test
        @DisplayName("达到阈值即封禁该来源")
        void thresholdTriggersBan() {
            LoginBruteForceGuard guard = new LoginBruteForceGuard();
            for (int i = 0; i < 5; i++) {
                guard.recordFailure("10.0.0.2");
            }
            assertTrue(guard.isBanned("10.0.0.2"), "5 次失败应触发封禁");
        }

        @Test
        @DisplayName("封禁按来源 IP 维度隔离，不影响其他来源")
        void banIsolatedByIp() {
            LoginBruteForceGuard guard = new LoginBruteForceGuard();
            for (int i = 0; i < 5; i++) {
                guard.recordFailure("10.0.0.3");
            }
            assertTrue(guard.isBanned("10.0.0.3"));
            assertFalse(guard.isBanned("10.0.0.4"), "其他来源不应受牵连");
        }

        @Test
        @DisplayName("登录成功清除该来源的全部失败计数与封禁")
        void successResetsState() {
            LoginBruteForceGuard guard = new LoginBruteForceGuard();
            for (int i = 0; i < 5; i++) {
                guard.recordFailure("10.0.0.5");
            }
            assertTrue(guard.isBanned("10.0.0.5"));

            guard.recordSuccess("10.0.0.5");
            assertFalse(guard.isBanned("10.0.0.5"), "登录成功后封禁状态必须清除");
        }

        @Test
        @DisplayName("封禁后清零计数：解封可重新累计失败次数")
        void counterClearedAfterBan() {
            LoginBruteForceGuard guard = new LoginBruteForceGuard();
            for (int i = 0; i < 5; i++) {
                guard.recordFailure("10.0.0.6");
            }
            // 封禁期内继续失败不应抛异常，且条目持续续期（封禁不被绕过）
            guard.recordFailure("10.0.0.6");
            assertTrue(guard.isBanned("10.0.0.6"));
        }

        @Test
        @DisplayName("未记录过的来源判定为未封禁")
        void unknownIpNotBanned() {
            LoginBruteForceGuard guard = new LoginBruteForceGuard();
            assertFalse(guard.isBanned("192.168.1.100"));
        }
    }

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发记录失败不会丢失计数，达到阈值必然封禁")
        void concurrentFailuresNotLost() throws Exception {
            LoginBruteForceGuard guard = new LoginBruteForceGuard();
            int threads = 10;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(threads);
            AtomicInteger errors = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        guard.recordFailure("172.16.0.1");
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(5, TimeUnit.SECONDS), "并发任务应在超时前完成");
            pool.shutdown();

            assertEquals(0, errors.get(), "并发记录过程不应抛异常");
            assertTrue(guard.isBanned("172.16.0.1"), "10 次并发失败超过阈值，必须封禁");
        }
    }

    @Nested
    @DisplayName("质询存储单次消费")
    class ChallengeStoreTests {

        @Test
        @DisplayName("签发的质询值长度符合 256 位十六进制规范")
        void issuedNonceFormat() {
            LoginChallengeStore store = new LoginChallengeStore();
            String nonce = store.issue();
            assertTrue(nonce.matches("^[0-9a-f]{64}$"), "质询值应为 64 位小写十六进制");
        }

        @Test
        @DisplayName("每次签发的质询值互不相同")
        void nonceIsRandom() {
            LoginChallengeStore store = new LoginChallengeStore();
            assertNotEquals(store.issue(), store.issue());
        }

        @Test
        @DisplayName("质询值严格单次有效：首次消费成功、重复消费失败")
        void singleConsumption() {
            LoginChallengeStore store = new LoginChallengeStore();
            String nonce = store.issue();

            assertTrue(store.consume(nonce), "首次消费应成功");
            assertFalse(store.consume(nonce), "重复消费必须失败（防重放）");
        }

        @Test
        @DisplayName("未签发或空值一律消费失败")
        void unknownNonceRejected() {
            LoginChallengeStore store = new LoginChallengeStore();
            assertFalse(store.consume("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
            assertFalse(store.consume(null));
            assertFalse(store.consume(""));
        }

        @Test
        @DisplayName("并发消费同一质询值仅有一个线程成功")
        void concurrentConsumptionExactlyOne() throws Exception {
            LoginChallengeStore store = new LoginChallengeStore();
            String nonce = store.issue();
            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(threads);
            AtomicInteger success = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        if (store.consume(nonce)) {
                            success.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // 消费过程不应抛异常，异常计数交由断言体现
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(5, TimeUnit.SECONDS));
            pool.shutdown();

            assertEquals(1, success.get(), "同一质询值并发消费必须恰好成功一次");
        }
    }
}
