package com.nju.comment.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 生成紧凑、可读的请求 ID。
 * <p>
 * 格式: {@code yyMMddHHmmss-XXXX} (17 字符)
 * <ul>
 *   <li>前 12 位: 时间戳，可直接解读生成时间（例 260312103045 → 2026-03-12 10:30:45）</li>
 *   <li>后 4 位: Base62 随机串，保证同一秒内唯一性（62⁴ ≈ 1477 万种组合）</li>
 * </ul>
 * 相比 UUID (36 字符) 压缩 53%，同时保留时间可读性。
 */
public final class RequestIdGenerator {

    private static final char[] BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int RANDOM_LEN = 4;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private RequestIdGenerator() {}

    /**
     * 生成请求 ID。
     * <p>
     * 示例: {@code 260312103045-a7Kp}
     *
     * @return 17 字符的请求 ID
     */
    public static String generate() {
        StringBuilder sb = new StringBuilder(17);
        sb.append(LocalDateTime.now().format(FMT)).append('-');
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < RANDOM_LEN; i++) {
            sb.append(BASE62[rng.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}
