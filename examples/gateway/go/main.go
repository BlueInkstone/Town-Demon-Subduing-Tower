// 墨铠网关最小 Go 客户端示例（docs/38 改动⑩）：仅用标准库，不发布 SDK 包。
// 运行：go run main.go
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
)

func main() {
	url := envOr("INKARMOR_GATEWAY_URL", "http://127.0.0.1:8080") + "/api/evaluate"
	token := envOr("INKARMOR_API_TOKEN", "")

	intent := map[string]any{
		"tool":           "deleteData",
		"args":           map[string]any{"table": "tmp"},
		"confidence":     0.9,
		"reason":         "清理过期表",
		"expectedEffect": "表被删除",
		"rollbackHint":   "无",
	}
	body, _ := json.Marshal(intent)

	req, _ := http.NewRequest("POST", url, bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Inkarmor-Token", token)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		// 网关不可达：fail-closed，绝不静默放行
		fmt.Println("网关不可达，拒绝执行：", err)
		os.Exit(1)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)

	var envelope struct {
		Code int `json:"code"`
		Data struct {
			Success bool   `json:"success"`
			Code    string `json:"code"`
		} `json:"data"`
	}
	_ = json.Unmarshal(data, &envelope)

	if resp.StatusCode != 200 || !envelope.Data.Success {
		fmt.Println("裁决拒绝，不执行：", string(data))
		os.Exit(1)
	}
	fmt.Println("裁决允许，执行原操作。code=", envelope.Data.Code)
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
