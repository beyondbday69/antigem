package hub

import (
	"archive/tar"
	"compress/gzip"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

const (
	AgyManifestBaseURL = "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app"
)

type AgyManifest struct {
	Version string `json:"version"`
	URL     string `json:"url"`
	SHA512  string `json:"sha512"`
}

// DownloadAndInstallAgy fetches the official AGY CLI release for arm64/amd64 and installs it to targetDir.
// It prioritizes android_arm64 on Android and falls back to linux_arm64.
func DownloadAndInstallAgy(targetDir string, logWriter io.Writer) (string, error) {
	if targetDir == "" {
		home, _ := os.UserHomeDir()
		targetDir = filepath.Join(home, ".local", "bin")
	}
	if err := os.MkdirAll(targetDir, 0755); err != nil {
		return "", fmt.Errorf("failed to create target dir %s: %w", targetDir, err)
	}

	arch := runtime.GOARCH
	if arch != "arm64" && arch != "amd64" {
		arch = "arm64"
	}

	platforms := []string{
		"android_" + arch,
		"linux_" + arch,
	}
	if runtime.GOOS != "android" && runtime.GOOS != "linux" {
		platforms = append([]string{runtime.GOOS + "_" + arch}, platforms...)
	}

	client := &http.Client{Timeout: 30 * time.Second}
	var manifest AgyManifest
	var chosenPlatform string

	for _, p := range platforms {
		manifestURL := fmt.Sprintf("%s/manifests/%s.json", AgyManifestBaseURL, p)
		if logWriter != nil {
			fmt.Fprintf(logWriter, " \033[90m[agy-install]\033[0m Querying release manifest: %s\n", manifestURL)
		}
		resp, err := client.Get(manifestURL)
		if err != nil || resp.StatusCode != http.StatusOK {
			if resp != nil {
				_ = resp.Body.Close()
			}
			continue
		}
		data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		_ = resp.Body.Close()
		if err != nil {
			continue
		}
		if err := json.Unmarshal(data, &manifest); err == nil && manifest.URL != "" {
			chosenPlatform = p
			break
		}
	}

	if manifest.URL == "" {
		return "", fmt.Errorf("failed to fetch valid AGY manifest for %s from %s", arch, AgyManifestBaseURL)
	}

	if logWriter != nil {
		fmt.Fprintf(logWriter, " \033[1;36m[agy-install]\033[0m Found Antigravity CLI v%s (%s)\n", manifest.Version, chosenPlatform)
		fmt.Fprintf(logWriter, " \033[90m[agy-install]\033[0m Downloading from %s...\n", manifest.URL)
	}

	destFile := filepath.Join(targetDir, "agy.va39")
	tmpFile := filepath.Join(targetDir, ".agy.va39.download")
	_ = os.Remove(tmpFile)

	dlClient := &http.Client{Timeout: 30 * time.Minute}
	dlResp, err := dlClient.Get(manifest.URL)
	if err != nil {
		return "", fmt.Errorf("failed to download release package: %w", err)
	}
	defer dlResp.Body.Close()
	if dlResp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("download failed with HTTP %d", dlResp.StatusCode)
	}

	isTarGz := strings.HasSuffix(manifest.URL, ".tar.gz")
	hasher := sha512.New()
	teeReader := io.TeeReader(dlResp.Body, hasher)

	f, err := os.OpenFile(tmpFile, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0755)
	if err != nil {
		return "", fmt.Errorf("failed to create temp file %s: %w", tmpFile, err)
	}

	if isTarGz {
		gzReader, err := gzip.NewReader(teeReader)
		if err != nil {
			_ = f.Close()
			_ = os.Remove(tmpFile)
			return "", fmt.Errorf("gzip error: %w", err)
		}
		tarReader := tar.NewReader(gzReader)
		found := false
		for {
			header, err := tarReader.Next()
			if err == io.EOF {
				break
			}
			if err != nil {
				_ = f.Close()
				_ = os.Remove(tmpFile)
				return "", fmt.Errorf("tar extract error: %w", err)
			}
			baseName := filepath.Base(header.Name)
			if baseName == "antigravity" || baseName == "agy" {
				if _, err := io.Copy(f, tarReader); err != nil {
					_ = f.Close()
					_ = os.Remove(tmpFile)
					return "", fmt.Errorf("failed to extract binary: %w", err)
				}
				found = true
				break
			}
		}
		_, _ = io.Copy(io.Discard, gzReader)
		_ = gzReader.Close()
		_ = f.Close()

		if !found {
			_ = os.Remove(tmpFile)
			return "", fmt.Errorf("binary 'antigravity' not found in downloaded archive")
		}
	} else {
		if _, err := io.Copy(f, teeReader); err != nil {
			_ = f.Close()
			_ = os.Remove(tmpFile)
			return "", fmt.Errorf("failed to write binary: %w", err)
		}
		_ = f.Close()
	}

	if manifest.SHA512 != "" {
		calculatedHash := hex.EncodeToString(hasher.Sum(nil))
		if !strings.EqualFold(calculatedHash, manifest.SHA512) {
			_ = os.Remove(tmpFile)
			return "", fmt.Errorf("checksum mismatch: expected %s, got %s", manifest.SHA512, calculatedHash)
		}
		if logWriter != nil {
			fmt.Fprintln(logWriter, " \033[32m[agy-install]\033[0m ✓ SHA512 Checksum verified successfully.")
		}
	}

	if err := os.Chmod(tmpFile, 0755); err != nil {
		log.Printf("Warning: chmod on %s: %v", tmpFile, err)
	}

	if err := os.Rename(tmpFile, destFile); err != nil {
		_ = os.Remove(destFile)
		if err2 := os.Rename(tmpFile, destFile); err2 != nil {
			return "", fmt.Errorf("failed to move downloaded binary to %s: %w", destFile, err2)
		}
	}

	if logWriter != nil {
		fmt.Fprintf(logWriter, " \033[1;32m[agy-install]\033[0m ✓ Successfully installed Antigravity CLI to %s\n", destFile)
	}

	return destFile, nil
}
