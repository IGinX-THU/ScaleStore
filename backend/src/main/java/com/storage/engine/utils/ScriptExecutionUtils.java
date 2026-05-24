package com.storage.engine.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class ScriptExecutionUtils {

    @Value("${resource.base-path:classpath:/}")
    private String resourceBasePath;

    public static class ScriptExecutionResult {
        private final int exitCode;
        private final String output;
        private final String error;

        public ScriptExecutionResult(int exitCode, String output, String error) {
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }

        public String getError() {
            return error;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    public File resolveScriptFile(String relativePath, String label) {
        try {
            if (isClasspathRoot()) {
                String classpathPath = joinClasspathPath(relativePath);
                ClassPathResource resource = new ClassPathResource(classpathPath);
                if (!resource.exists()) {
                    throw new RuntimeException(label + "不存在: classpath:" + classpathPath);
                }
                File scriptFile = materializeResourceFile(resource, relativePath);
                if (!scriptFile.canExecute()) {
                    scriptFile.setExecutable(true);
                }
                return scriptFile;
            }

            String root = removeFilePrefix(resourceBasePath);
            File scriptFile = new File(root, relativePath.replace("/", File.separator));
            if (!scriptFile.exists()) {
                throw new RuntimeException(label + "不存在: " + scriptFile.getPath());
            }
            return scriptFile;
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("加载" + label + "失败: " + e.getMessage(), e);
        }
    }

    public File resolveResourceFile(String relativePath, String label) {
        try {
            if (isClasspathRoot()) {
                String classpathPath = joinClasspathPath(relativePath);
                ClassPathResource resource = new ClassPathResource(classpathPath);
                if (!resource.exists()) {
                    throw new RuntimeException(label + "不存在: classpath:" + classpathPath);
                }
                return materializeResourceFile(resource, relativePath);
            }

            String root = removeFilePrefix(resourceBasePath);
            File file = new File(root, relativePath.replace("/", File.separator));
            if (!file.exists() || !file.isFile()) {
                throw new RuntimeException(label + "不存在: " + file.getPath());
            }
            return file;
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("加载" + label + "失败: " + e.getMessage(), e);
        }
    }

    public File resolveOptionalResourceDirectory(String relativePath) {
        try {
            if (isClasspathRoot()) {
                String classpathPath = joinClasspathPath(relativePath);
                ClassPathResource dirResource = new ClassPathResource(classpathPath);
                if (!dirResource.exists()) {
                    return null;
                }

                try {
                    File file = dirResource.getFile();
                    if (file.exists() && file.isDirectory()) {
                        return file;
                    }
                } catch (Exception ignored) {
                }

                return materializeResourceDirectory(classpathPath, relativePath);
            }

            String root = removeFilePrefix(resourceBasePath);
            File dir = new File(root, relativePath.replace("/", File.separator));
            if (!dir.exists() || !dir.isDirectory()) {
                return null;
            }
            return dir;
        } catch (Exception e) {
            return null;
        }
    }

    public ScriptExecutionResult executeScript(List<String> command, int timeoutSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        
        Map<String, String> env = builder.environment();
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.remove("VSCODE_GIT_ASKPASS_NODE");
        env.remove("VSCODE_GIT_ASKPASS_MAIN");
        env.remove("VSCODE_GIT_IPC_HANDLE");

        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException e) {
            }
        });

        Thread errorReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line).append('\n');
                }
            } catch (IOException e) {
            }
        });

        outputReader.start();
        errorReader.start();

        boolean done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            throw new RuntimeException("脚本执行超时");
        }

        outputReader.join();
        errorReader.join();

        return new ScriptExecutionResult(process.exitValue(), output.toString(), error.toString());
    }

    private File materializeResourceFile(ClassPathResource resource, String relativePath) throws Exception {
        String name = new File(relativePath).getName();
        File tmpScript = File.createTempFile("scalestore-", "-" + name);
        
        if (relativePath.endsWith(".sh")) {
            try (InputStream input = resource.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(new FileOutputStream(tmpScript), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.write('\n');
                }
            }
        } else {
            try (InputStream input = resource.getInputStream()) {
                Files.copy(input, tmpScript.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        
        tmpScript.deleteOnExit();
        return tmpScript;
    }

    private File materializeResourceDirectory(String classpathPath, String relativePath) throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:" + classpathPath + "/**");
        if (resources == null || resources.length == 0) {
            return null;
        }

        File baseDir = Files.createTempDirectory("scalestore-" + new File(relativePath).getName() + "-").toFile();
        markDeleteOnExit(baseDir);

        String normalizedPath = classpathPath.replace('\\', '/');
        String anchor = normalizedPath + "/";
        boolean copied = false;

        for (Resource resource : resources) {
            if (resource == null || !resource.exists() || !resource.isReadable()) {
                continue;
            }

            String url = resource.getURL().toString().replace('\\', '/');
            int idx = url.indexOf(anchor);
            if (idx < 0) {
                continue;
            }

            String sub = url.substring(idx + anchor.length());
            if (sub.isEmpty() || sub.endsWith("/")) {
                continue;
            }

            File target = new File(baseDir, sub);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (InputStream input = resource.getInputStream()) {
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            copied = true;
        }

        return copied ? baseDir : null;
    }

    private void markDeleteOnExit(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    markDeleteOnExit(child);
                }
            }
        }
        file.deleteOnExit();
    }

    private boolean isClasspathRoot() {
        String root = resourceBasePath == null ? "" : resourceBasePath.trim();
        return root.isEmpty() || ".".equals(root) || root.startsWith("classpath:");
    }

    private String joinClasspathPath(String relativePath) {
        String root = resourceBasePath == null ? "" : resourceBasePath.trim();
        if (root.startsWith("classpath:")) {
            root = root.substring("classpath:".length());
        }
        root = root.replace('\\', '/');
        while (root.startsWith("/")) {
            root = root.substring(1);
        }
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.isEmpty() || ".".equals(root)) {
            return relativePath;
        }
        return root + "/" + relativePath;
    }

    private String removeFilePrefix(String path) {
        String value = path == null ? "" : path.trim();
        if (value.startsWith("file:")) {
            return value.substring("file:".length());
        }
        return value;
    }
}
