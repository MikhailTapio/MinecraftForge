/*
 * Minecraft Forge
 * Copyright (c) 2016-2021.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fml.loading.moddiscovery;

import net.minecraftforge.forgespi.language.ModFileScanData;
import net.minecraftforge.fml.loading.LoadingModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static net.minecraftforge.fml.loading.LogMarkers.SCAN;

public class BackgroundScanHandler
{
    private static final Logger LOGGER = LogManager.getLogger();
    private final ExecutorService modContentScanner;
    private final List<ModFile> pendingFiles;
    private final List<ModFile> scannedFiles;
    private final List<ModFile> allFiles;
    private final List<ModFile> modFiles;
    private LoadingModList loadingModList;

    @SuppressWarnings("UnstableApiUsage")
    public BackgroundScanHandler(final List<ModFile> modFiles) {
        this.modFiles = modFiles;
        modContentScanner = Executors.newSingleThreadExecutor(r -> {
            final Thread thread = Executors.defaultThreadFactory().newThread(r);
            thread.setDaemon(true);
            return thread;
        });
        scannedFiles = new ArrayList<>();
        pendingFiles = new ArrayList<>();
        allFiles = new ArrayList<>();
    }

    public List<ModFile> getModFiles() {
        return modFiles;
    }

    public void submitForScanning(final ModFile file) {
        if (modContentScanner.isShutdown()) {
            throw new IllegalStateException("Scanner has shutdown");
        }
        allFiles.add(file);
        pendingFiles.add(file);
        final CompletableFuture<ModFileScanData> future = CompletableFuture.supplyAsync(file::compileContent, modContentScanner)
                .whenComplete(file::setScanResult)
                .whenComplete((r,t)-> this.addCompletedFile(file,r,t));
        file.setFutureScanResult(future);
    }

    private void addCompletedFile(final ModFile file, final ModFileScanData modFileScanData, final Throwable throwable) {
        if (throwable != null) {
            LOGGER.error(SCAN,"An error occurred scanning file {}", file, throwable);
        }
        pendingFiles.remove(file);
        scannedFiles.add(file);
    }

    public void setLoadingModList(LoadingModList loadingModList)
    {
        this.loadingModList = loadingModList;
    }

    public LoadingModList getLoadingModList()
    {
        return loadingModList;
    }

    public void waitForScanToComplete(final Runnable ticker) {
        boolean interrupted = false;
        boolean succeeded = false;
        modContentScanner.shutdown();
        do {
            ticker.run();
            try {
                succeeded = modContentScanner.awaitTermination(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        } while (!modContentScanner.isShutdown());
        if (interrupted) Thread.currentThread().interrupt();
        if (!succeeded) throw new IllegalStateException("Failed to complete mod scan");
    }
}