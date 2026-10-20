// Package ecocrypto exposes only file-to-file encryption to Android.
package ecocrypto

import (
	"archive/tar"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"

	"filippo.io/age"
)

const maxBytes int64 = 256 * 1024 * 1024

// Encrypt streams a strict three-file USTAR archive directly into age encryption.
// expectedJSON maps filenames to SHA-256 values trusted by the local snapshot ledger.
// The caller publishes output only after this function returns successfully.
func Encrypt(directory, output, publicRecipients, expectedJSON string) (err error) {
	var expected map[string]string
	if json.Unmarshal([]byte(expectedJSON), &expected) != nil || len(expected) != 3 {
		return errors.New("INVALID_EXPECTATIONS")
	}
	var recipients []age.Recipient
	keys := strings.Split(publicRecipients, "\n")
	if len(keys) != 2 || keys[0] == keys[1] {
		return errors.New("TWO_RECIPIENTS_REQUIRED")
	}
	for _, key := range keys {
		r, e := age.ParseX25519Recipient(key)
		if e != nil {
			return errors.New("INVALID_RECIPIENT")
		}
		recipients = append(recipients, r)
	}
	root, e := os.Lstat(directory)
	if e != nil || !root.IsDir() || root.Mode()&os.ModeSymlink != 0 {
		return errors.New("INVALID_SOURCE")
	}
	f, e := os.OpenFile(output, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if e != nil {
		return errors.New("OUTPUT_EXISTS_OR_UNAVAILABLE")
	}
	defer func() {
		f.Close()
		if err != nil {
			os.Remove(output)
		}
	}()
	encrypted, e := age.Encrypt(f, recipients...)
	if e != nil {
		return errors.New("ENCRYPTION_FAILED")
	}
	archive := tar.NewWriter(encrypted)
	var total int64
	for _, name := range []string{"event.json", "manifest.json", "video.mp4"} {
		info, e := os.Lstat(filepath.Join(directory, name))
		if e != nil || !info.Mode().IsRegular() || info.Size() <= 0 {
			return errors.New("INVALID_SOURCE")
		}
		total += info.Size()
		if total > maxBytes-1024*1024 {
			return errors.New("PACKAGE_TOO_LARGE")
		}
		if e = archive.WriteHeader(&tar.Header{Name: name, Size: info.Size(), Mode: 0600, Format: tar.FormatUSTAR}); e != nil {
			return errors.New("ARCHIVE_FAILED")
		}
		source, e := os.Open(filepath.Join(directory, name))
		if e != nil {
			return errors.New("SOURCE_UNAVAILABLE")
		}
		h := sha256.New()
		count, e := io.Copy(io.MultiWriter(archive, h), source)
		source.Close()
		if e != nil || count != info.Size() || hex.EncodeToString(h.Sum(nil)) != expected[name] {
			return errors.New("SOURCE_HASH_CHANGED")
		}
	}
	if archive.Close() != nil || encrypted.Close() != nil || f.Sync() != nil {
		return errors.New("ENCRYPTION_FAILED")
	}
	return nil
}
