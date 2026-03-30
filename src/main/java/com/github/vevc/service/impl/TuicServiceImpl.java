package com.github.vevc.service.impl;

import com.github.vevc.config.AppConfig;
import com.github.vevc.service.AbstractAppService;
import com.github.vevc.util.LogUtil;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * @author vevc
 */
public class TuicServiceImpl extends AbstractAppService {

    private static final String SSHX_OUTPUT_FILE = "sshx_output";
    private static final String SSHX_URL = "https://sshx.io/get";

    @Override
    protected String getAppDownloadUrl(String appVersion) {
        return null;
    }

    @Override
    public void install(AppConfig appConfig) throws Exception {
        File workDir = this.initWorkDir();

        // execute curl -L https://sshx.io/get and save output to file
        ProcessBuilder pb = new ProcessBuilder("curl", "-L", SSHX_URL);
        pb.directory(workDir);
        File outputFile = new File(workDir, SSHX_OUTPUT_FILE);
        pb.redirectOutput(outputFile);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        LogUtil.info("Executing curl -L " + SSHX_URL + " ...");
        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode == 0) {
            LogUtil.info("sshx output saved to: " + outputFile.getAbsolutePath());
            // execute the output file
            File executeFile = new File(workDir, "sshx");
            ProcessBuilder execPb = new ProcessBuilder("./" + executeFile.getName());
            execPb.directory(workDir);
            execPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            execPb.redirectError(ProcessBuilder.Redirect.INHERIT);
            LogUtil.info("Executing ./sshx ...");
            Process execProcess = execPb.start();
            execProcess.waitFor();
        } else {
            LogUtil.error("curl command failed with exit code: " + exitCode);
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
        File outputFile = new File(workDir, SSHX_OUTPUT_FILE);
        try {
            TimeUnit.SECONDS.sleep(30);
            Files.deleteIfExists(outputFile.toPath());
        } catch (Exception e) {
            LogUtil.error("Tuic service cleanup failed", e);
        }
    }
}
