package com.github.shikejing.codesplitplannerjava.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * A deterministic packaging unit. The bundle id is derived purely from the
 * sorted member list, so the same members always yield the same id regardless
 * of input order or concurrency.
 *
 * @param id      deterministic id: {@code bundle-} + first 16 hex chars of the
 *                SHA-256 of the newline-joined sorted member ids
 * @param members module ids, sorted lexicographically
 * @param timing  load timing of the whole bundle
 * @param shared  true when the bundle is reachable from more than one entry
 */
public record Bundle(String id, List<String> members, LoadTiming timing, boolean shared) {

    public static String idOf(List<String> sortedMembers) {
        return "bundle-" + sha256Hex(String.join("\n", sortedMembers)).substring(0, 16);
    }

    public static Bundle of(List<String> sortedMembers, LoadTiming timing, boolean shared) {
        return new Bundle(idOf(sortedMembers), List.copyOf(sortedMembers), timing, shared);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
