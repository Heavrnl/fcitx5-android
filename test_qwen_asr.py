import os
import websocket

# 测试前，请将下方替换为你的实际 API Key
API_KEY = "sk-e17cf749c0514870a1344ea80e3017e4"

QWEN_MODEL = "qwen3-asr-flash-realtime"
baseUrl = "wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime"
url = f"{baseUrl}?model={QWEN_MODEL}"

print(f"Connecting to server: {url}")

headers = [
    "Authorization: Bearer " + API_KEY,
    "OpenAI-Beta: realtime=v1"
]

def on_open(ws):
    print("\n✅ 连接成功！")
    print("API Key 鉴权通过，模型服务正常可用。")
    ws.close()

def on_error(ws, error):
    print(f"\n❌ 连接发生错误: {error}")
    # 打印可能是 401 权限问题

def on_close(ws, close_status_code, close_msg):
    print(f"\nWebSocket 连接已关闭 (代码: {close_status_code}, 原因: {close_msg})")

if __name__ == "__main__":
    # 开启这个可以查看底层的 HTTP 头和响应握手信息
    websocket.enableTrace(True)
    
    ws = websocket.WebSocketApp(url,
                                header=headers,
                                on_open=on_open,
                                on_error=on_error,
                                on_close=on_close)
    
    ws.run_forever()
