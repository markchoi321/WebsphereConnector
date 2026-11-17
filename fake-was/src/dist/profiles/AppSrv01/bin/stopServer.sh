#!/bin/sh
# 模拟 WAS 的 stopServer.sh，仅用于插件开发与测试
WAS_HOME="@WAS_HOME@"
PROFILE_HOME="$WAS_HOME/profiles/AppSrv01"
SERVER=""
while [ $# -gt 0 ]; do
  case "$1" in
    -profileName|-username|-password|-timeout|-statusport|-logfile) shift 2 ;;
    -*) shift ;;
    *) SERVER="$1"; shift ;;
  esac
done
exec "$WAS_HOME/java_1.8_64/jre/bin/java" -classpath "$WAS_HOME/lib/fake-was.jar" com.wasce.fakewas.FakeStopServer "$PROFILE_HOME/config" Cell01 Node01 "$SERVER"
