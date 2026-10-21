#!/bin/sh
# Startup of the gateway image with and without its AOT cache; see README.md.
# Usage: measure-container.sh <image> <runs>. Needs the Docker network cosec-aot with a Redis container cosec-aot-redis.
# The last column counts [aot] log lines: 0 means the cache was used (or, for none, not tried).
IMG=$1; N=$2
echo "run,mode,started_s,process_s,rss_kb,load1"
i=1
while [ $i -le $N ]; do
  for mode in none aot; do
    if [ $mode = none ]; then E="-e JDK_JAVA_OPTIONS="; else E=""; fi
    L=$(sysctl -n vm.loadavg | awk '{print $2}')
    docker run -d --name cosec-aot-m --network cosec-aot $E -e SPRING_DATA_REDIS_URL=redis://cosec-aot-redis:6379 -e COSEC_JWT_SECRET=measure-only-0123456789abcdef0123 $IMG >/dev/null
    until docker logs cosec-aot-m 2>&1 | grep -q "Started Gateway"; do sleep 0.1; done
    sleep 1
    R=$(docker exec cosec-aot-m sh -c "grep VmRSS /proc/1/status" | awk '{print $2}')
    T=$(docker logs cosec-aot-m 2>&1 | grep -o 'in [0-9.]* seconds (process running for [0-9.]*)' | sed 's/in \([0-9.]*\) seconds (process running for \([0-9.]*\))/\1,\2/')
    A=$(docker logs cosec-aot-m 2>&1 | grep -c "\[aot\]")
    echo "$i,$mode,$T,$R,$L,aotlines=$A"
    docker rm -f cosec-aot-m >/dev/null; sleep 2
  done
  i=$((i+1))
done
