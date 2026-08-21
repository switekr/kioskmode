package com.kiosklauncher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class BrowserLocator {

    private BrowserLocator() {
    }

    private static final List<String> CANDIDATES = List.of(
            expand("%ProgramFiles(x86)%\\Microsoft\\Edge\\Application\\msedge.exe"),
            expand("%ProgramFiles%\\Microsoft\\Edge\\Application\\msedge.exe"),
            expand("%ProgramFiles%\\Google\\Chrome\\Application\\chrome.exe"),
            expand("%ProgramFiles(x86)%\\Google\\Chrome\\Application\\chrome.exe")
    );

    static String find() {
        return CANDIDATES.stream()
                .filter(p -> Files.exists(Path.of(p)))
                .findFirst()
                .orElse(null);
    }

    // Java has no built-in %VAR% expansion (System.getenv only does exact
    // lookups) - do it by hand for the two variables actually used here.
    private static String expand(String pathWithVars) {
        return pathWithVars
                .replace("%ProgramFiles(x86)%", envOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)"))
                .replace("%ProgramFiles%", envOrDefault("ProgramFiles", "C:\\Program Files"));
    }

    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return v != null ? v : fallback;
    }
}
