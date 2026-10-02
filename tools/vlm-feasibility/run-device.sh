#!/system/bin/sh
# Engineering harness only. Does not access the production application.
set -u
cd /data/local/tmp/ecocapture-qwen3vl-spike || exit 1
count="$1"
case "$count" in 1|3|5) ;; *) exit 2 ;; esac
prefix="$2"
case "$prefix" in run-"$count"-v2-*) ;; *) exit 2 ;; esac
dumpsys thermalservice > "${prefix}-thermal-before.txt"
cat /proc/meminfo > "${prefix}-mem-before.txt"
images=''
i=0
while [ "$i" -lt "$count" ]; do
    if [ -n "$images" ]; then images="$images,"; fi
    images="${images}test-1.jpeg"
    i=$((i + 1))
done
start=$(date +%s)
./llama-mtmd-cli -m Qwen3VL-4B-Instruct-Q4_K_M.gguf \
    --mmproj mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf \
    --no-mmproj-offload -ngl 0 -c 8192 -t 4 -tb 4 -n 384 \
    --temp 0 --seed 0 --no-warmup --log-verbosity 4 --image "$images" \
    --system-prompt "$(cat system-prompt.txt)" --file user-prompt.txt \
    > "${prefix}-output.txt" 2> "${prefix}-runtime.txt" &
pid=$!
echo "pid=$pid" > "${prefix}-summary.txt"
echo 'epoch_s VmRSS_kB VmHWM_kB' > "${prefix}-rss.txt"
while kill -0 "$pid" 2>/dev/null; do
    awk -v now="$(date +%s)" '/VmRSS:/ {rss=$2} /VmHWM:/ {hwm=$2} END {print now, rss, hwm}' "/proc/$pid/status" >> "${prefix}-rss.txt" 2>/dev/null
    sleep 1
done
wait "$pid"
result=$?
end=$(date +%s)
echo "exit_code=$result" >> "${prefix}-summary.txt"
echo "elapsed_seconds=$((end - start))" >> "${prefix}-summary.txt"
dumpsys thermalservice > "${prefix}-thermal-after.txt"
cat /proc/meminfo > "${prefix}-mem-after.txt"
cat "${prefix}-summary.txt"
exit "$result"
