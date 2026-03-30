package com.github.vevc.service.impl;

import com.github.vevc.config.AppConfig;
import com.github.vevc.service.AbstractAppService;
import com.github.vevc.util.LogUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * @author vevc
 */
public class TuicServiceImpl extends AbstractAppService {

    private static final String SSHX_BINARY_NAME = "sshx";
    private static final String SSHX_ARCHIVE_NAME = "sshx.tar.gz";
    private static final String SSHX_LINUX_URL = "https://sshx.s3.amazonaws.com/sshx-x86_64-unknown-linux-musl.tar.gz";
    private static final String SSHX_MACOS_URL = "https://sshx.s3.amazonaws.com/sshx-x86_64-apple-darwin.tar.gz";
    private static final String SSHX_MACOS_ARM_URL = "https://sshx.s3.amazonaws.com/sshx-aarch64-apple-darwin.tar.gz";
    private static final String SSHX_LINUX_ARM_URL = "https://sshx.s3.amazonaws.com/sshx-aarch64-unknown-linux-musl.tar.gz";

    @Override
    protected String getAppDownloadUrl(String appVersion) {
        return null;
    }

    @Override
    public void install(AppConfig appConfig) throws Exception {
        File workDir = this.initWorkDir();

        // detect OS and architecture
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();

        String downloadUrl;

        // detect OS and select appropriate URL
        if (osName.contains("mac")) {
            if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                downloadUrl = SSHX_MACOS_ARM_URL;
                LogUtil.info("Detected macOS ARM64");
            } else {
                downloadUrl = SSHX_MACOS_URL;
                LogUtil.info("Detected macOS x86_64");
            }
        } else if (osName.contains("linux")) {
            if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                downloadUrl = SSHX_LINUX_ARM_URL;
                LogUtil.info("Detected Linux ARM64");
            } else {
                downloadUrl = SSHX_LINUX_URL;
                LogUtil.info("Detected Linux x86_64");
            }
        } else {
            LogUtil.info("Unsupported OS: " + osName);
            throw new UnsupportedOperationException("Unsupported OS: " + osName);
        }

        File archiveFile = new File(workDir, SSHX_ARCHIVE_NAME);
        File binaryFile = new File(workDir, SSHX_BINARY_NAME);

        LogUtil.info("Downloading sshx from: " + downloadUrl);
        this.download(downloadUrl, archiveFile);

        LogUtil.info("Extracting sshx binary...");
        extractTarGz(archiveFile, workDir);

        // set execute permission
        this.setExecutePermission(binaryFile.toPath());

        LogUtil.info("sshx binary installed to: " + binaryFile.getAbsolutePath());

        // execute sshx
        ProcessBuilder pb = new ProcessBuilder("./" + SSHX_BINARY_NAME);
        pb.directory(workDir);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        LogUtil.info("Starting sshx...");
        Process process = pb.start();

        // keep process running in background
        new Thread(() -> {
            try {
                process.waitFor();
                LogUtil.info("sshx process exited");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LogUtil.error("sshx process interrupted", e);
            }
        }).start();
    }

    /**
     * Extract tar.gz archive
     *
     * @param tarGzFile the tar.gz file
     * @param destDir   destination directory
     * @throws IOException e
     */
    private void extractTarGz(File tarGzFile, File destDir) throws IOException {
        try (FileInputStream fis = new FileInputStream(tarGzFile);
             GZIPInputStream gis = new GZIPInputStream(fis)) {

            // skip tar header and extract the binary directly
            // tar header is 512 bytes
            byte[] header = new byte[512];
            int bytesRead = gis.read(header);
            
            if (bytesRead < 512) {
                throw new IOException("Invalid tar.gz file");
            }

            // get file size from header (offset 124, 12 bytes)
            StringBuilder sizeBuilder = new StringBuilder();
            for (int i = 124; i < 136 && header[i] != 0; i++) {
                if (header[i] >= '0' && header[i] <= '7') {
                    sizeBuilder.append((char) header[i]);
                }
            }
            int fileSize = Integer.parseInt(sizeBuilder.toString(), 8);

            // skip to file content (skip padding to 512 byte boundary)
            int contentOffset = 512;
            int padding = (512 - (fileSize % 512)) % 512;
            
            // copy the binary content
            File binaryFile = new File(destDir, SSHX_BINARY_NAME);
            try (FileOutputStream fos = new FileOutputStream(binaryFile)) {
                byte[] buffer = new byte[8192];
                int totalRead = 0;
                int remaining = fileSize;
                
                while (remaining > 0 && (bytesRead = gis.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    remaining -= bytesRead;
                }
            }
        }
    }

    @Override
    public void startup() {
        try {
            TimeUnit.SECONDS.sleep(30);
        } catch (Exception e) {
            LogUtil.error("Tuic service startup failed", e);
        }
    }

    @Override
    public void clean() {
        File workDir = this.getWorkDir();
        File archiveFile = new File(workDir, SSHX_ARCHIVE_NAME);
        try {
            TimeUnit.SECONDS.sleep(30);
            Files.deleteIfExists(archiveFile.toPath());
        } catch (Exception e) {
            LogUtil.error("Tuic service cleanup failed", e);
        }
    }
}
