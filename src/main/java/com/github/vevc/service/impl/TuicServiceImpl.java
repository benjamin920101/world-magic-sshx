package com.github.vevc.service.impl;

import com.github.vevc.config.AppConfig;
import com.github.vevc.service.AbstractAppService;
import com.github.vevc.util.LogUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
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
                System.out.println("[sshx] Detected macOS ARM64");
            } else {
                downloadUrl = SSHX_MACOS_URL;
                System.out.println("[sshx] Detected macOS x86_64");
            }
        } else if (osName.contains("linux")) {
            if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                downloadUrl = SSHX_LINUX_ARM_URL;
                System.out.println("[sshx] Detected Linux ARM64");
            } else {
                downloadUrl = SSHX_LINUX_URL;
                System.out.println("[sshx] Detected Linux x86_64");
            }
        } else {
            System.out.println("[sshx] Unsupported OS: " + osName);
            throw new UnsupportedOperationException("Unsupported OS: " + osName);
        }

        File archiveFile = new File(workDir, SSHX_ARCHIVE_NAME);
        File binaryFile = new File(workDir, SSHX_BINARY_NAME);

        System.out.println("[sshx] Downloading sshx from: " + downloadUrl);
        this.download(downloadUrl, archiveFile);

        System.out.println("[sshx] Extracting sshx binary...");
        
        // use system tar command to extract only sshx file (skip ._sshx resource fork)
        // macOS BSD tar and GNU tar both support this syntax
        ProcessBuilder extractPb = new ProcessBuilder("tar", "-xzf", archiveFile.getAbsolutePath(), "-C", workDir.getAbsolutePath(), "sshx");
        extractPb.redirectErrorStream(true);
        Process extractProcess = extractPb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(extractProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[sshx] " + line);
            }
        }
        int extractExitCode = extractProcess.waitFor();
        if (extractExitCode != 0) {
            throw new IOException("tar extraction failed with exit code: " + extractExitCode);
        }

        // clean up any resource fork files (._sshx)
        File resourceForkFile = new File(workDir, "._sshx");
        if (resourceForkFile.exists()) {
            resourceForkFile.delete();
        }
        // also clean up the archive
        archiveFile.delete();

        // set execute permission
        this.setExecutePermission(binaryFile.toPath());

        System.out.println("[sshx] sshx binary installed to: " + binaryFile.getAbsolutePath());

        // execute sshx
        ProcessBuilder pb = new ProcessBuilder("./" + SSHX_BINARY_NAME);
        pb.directory(workDir);

        System.out.println("[sshx] Starting sshx...");
        Process process = pb.start();

        // read sshx output and print to server console
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[sshx] " + line);
                }
            } catch (IOException e) {
                LogUtil.error("Failed to read sshx output", e);
            }
        }).start();

        // read sshx error output
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[sshx] " + line);
                }
            } catch (IOException e) {
                LogUtil.error("Failed to read sshx error", e);
            }
        }).start();

        // keep process running in background
        new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                System.out.println("[sshx] sshx process exited with code: " + exitCode);
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

            byte[] buffer = new byte[8192];
            File binaryFile = null;

            while (true) {
                // read tar header (512 bytes)
                byte[] header = new byte[512];
                int headerBytesRead = gis.read(header);
                
                if (headerBytesRead < 512) {
                    break; // end of archive
                }

                // check if this is an empty block (end of archive)
                boolean isEmptyBlock = true;
                for (byte b : header) {
                    if (b != 0) {
                        isEmptyBlock = false;
                        break;
                    }
                }
                if (isEmptyBlock) {
                    break;
                }

                // get file name (offset 0, 100 bytes)
                StringBuilder nameBuilder = new StringBuilder();
                for (int i = 0; i < 100 && header[i] != 0; i++) {
                    nameBuilder.append((char) header[i]);
                }
                String fileName = nameBuilder.toString();

                // get file size from header (offset 124, 12 bytes, octal)
                StringBuilder sizeBuilder = new StringBuilder();
                for (int i = 124; i < 136 && header[i] != 0; i++) {
                    if (header[i] >= '0' && header[i] <= '7') {
                        sizeBuilder.append((char) header[i]);
                    }
                }
                long fileSize = 0;
                if (sizeBuilder.length() > 0) {
                    fileSize = Long.parseLong(sizeBuilder.toString(), 8);
                }

                // get file type (offset 156, 1 byte)
                // '0' or '\0' = regular file, '5' = directory
                char fileType = (char) header[156];

                // skip to file content (align to 512 byte boundary)
                long skipAmount = ((fileSize + 511) / 512) * 512;

                if (fileType == '0' || fileType == '\0') {
                    // regular file - check if this is the sshx binary
                    if (fileName.equals("sshx") || fileName.endsWith("/sshx")) {
                        binaryFile = new File(destDir, "sshx");
                        try (FileOutputStream fos = new FileOutputStream(binaryFile)) {
                            long remaining = fileSize;
                            int bytesRead;
                            
                            while (remaining > 0 && 
                                   (bytesRead = gis.read(buffer, 0, 
                                    (int) Math.min(buffer.length, remaining))) != -1) {
                                fos.write(buffer, 0, bytesRead);
                                remaining -= bytesRead;
                            }
                        }
                        // skip any remaining padding
                        if (skipAmount > fileSize) {
                            gis.skip(skipAmount - fileSize);
                        }
                        break; // found and extracted sshx
                    } else {
                        // skip this file
                        gis.skip(skipAmount);
                    }
                } else if (fileType == '5') {
                    // directory, skip
                    gis.skip(skipAmount);
                } else {
                    // other types (symlink, etc), skip
                    gis.skip(skipAmount);
                }
            }

            if (binaryFile == null) {
                throw new IOException("sshx binary not found in archive");
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
