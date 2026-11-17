#!/bin/sh
# 模拟 WAS 的 startServer.sh，仅用于插件开发与测试。参数形式与真实脚本一致。
WAS_HOME="@WAS_HOME@"
PROFILE_HOME="$WAS_HOME/profiles/AppSrv01"
JAVA="$WAS_HOME/java_1.8_64/jre/bin/java"
SERVER=""
SCRIPT=""
while [ $# -gt 0 ]; do
  case "$1" in
    -script) SCRIPT="$2"; shift 2 ;;
    -profileName|-username|-password|-timeout|-statusport|-logfile) shift 2 ;;
    -*) shift ;;
    *) SERVER="$1"; shift ;;
  esac
done
if [ -z "$SERVER" ]; then
  echo "ADMU0116I: 缺少服务器名"
  exit 1
fi
mkdir -p "$PROFILE_HOME/logs/$SERVER"
echo "ADMU0116I: Tool information is being logged in file $PROFILE_HOME/logs/$SERVER/startServer.log"
echo "ADMU3100I: Reading configuration for server: $SERVER"
if [ -n "$SCRIPT" ]; then
  cat > "$SCRIPT" <<EOS
#!/bin/sh
# 由 startServer.sh -script 生成（模拟）
cd "$PROFILE_HOME"
"$JAVA" -Xms64m -Xmx256m -Dwas.install.root="$WAS_HOME" -Duser.install.root="$PROFILE_HOME" \$FAKEWAS_OPTS -classpath "$WAS_HOME/lib/fake-was.jar" com.wasce.fakewas.FakeWasServer com.ibm.ws.runtime.WsServer "$PROFILE_HOME/config" Cell01 Node01 $SERVER
EOS
  chmod +x "$SCRIPT"
  echo "ADMU3300I: Launch script for server created: $SCRIPT"
  exit 0
fi
nohup "$JAVA" -Xms64m -Xmx256m $FAKEWAS_OPTS -classpath "$WAS_HOME/lib/fake-was.jar" com.wasce.fakewas.FakeWasServer com.ibm.ws.runtime.WsServer "$PROFILE_HOME/config" Cell01 Node01 "$SERVER" > "$PROFILE_HOME/logs/$SERVER/native_stdout.log" 2>&1 &
PID=$!
echo "ADMU3200I: Server launched. Waiting for initialization status."
i=0
while [ $i -lt 120 ]; do
  if grep -q "WSVR0001I" "$PROFILE_HOME/logs/$SERVER/SystemOut.log" 2>/dev/null; then
    echo "ADMU3000I: Server $SERVER open for e-business; process id is $PID"
    exit 0
  fi
  if ! kill -0 $PID 2>/dev/null; then
    echo "ADMU3011E: Server launched but failed initialization."
    exit 1
  fi
  sleep 1
  i=$((i+1))
done
echo "ADMU3011E: Server launched but failed initialization (timeout)."
exit 1
