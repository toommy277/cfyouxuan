package main

import (
	"bufio"
	"context"
	"crypto/tls"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed index.html
var page []byte

var regions = map[string]string{"HK": "中国香港 HK", "TW": "中国台湾 TW", "SG": "新加坡 SG", "JP": "日本 JP", "US": "美国 US", "KR": "韩国 KR", "GB": "英国 GB", "DE": "德国 DE", "RU": "俄罗斯 RU", "NL": "荷兰 NL", "SE": "瑞典 SE", "AU": "澳大利亚 AU", "VN": "越南 VN", "IN": "印度 IN", "LV": "拉脱维亚 LV", "FI": "芬兰 FI"}
var routeCodes = map[string]bool{"NRT": true, "KIX": true, "HND": true, "HKG": true, "SIN": true, "TPE": true, "ICN": true, "LAX": true, "SJC": true, "FRA": true, "AMS": true, "LHR": true, "CDG": true, "ORD": true, "IAD": true, "SEA": true, "DFW": true, "MEL": true, "SYD": true}

type Item map[string]interface{}
type Config struct {
	WorkerURL    string `json:"workerUrl"`
	WorkerSecret string `json:"workerSecret"`
}

var configMu sync.Mutex

func configPath() string {
	d, err := os.UserConfigDir()
	if err != nil {
		d = "."
	}
	return filepath.Join(d, "CFYouxuan", "config.json")
}
func readConfig() Config {
	configMu.Lock()
	defer configMu.Unlock()
	var c Config
	b, e := os.ReadFile(configPath())
	if e == nil {
		_ = json.Unmarshal(b, &c)
	}
	return c
}
func saveConfig(c Config) error {
	configMu.Lock()
	defer configMu.Unlock()
	p := configPath()
	if e := os.MkdirAll(filepath.Dir(p), 0700); e != nil {
		return e
	}
	b, _ := json.MarshalIndent(c, "", "  ")
	return os.WriteFile(p, b, 0600)
}
func str(v interface{}) string {
	if v == nil {
		return ""
	}
	return fmt.Sprint(v)
}
func num(v interface{}, def int) int {
	switch x := v.(type) {
	case float64:
		return int(x)
	case int:
		return x
	case string:
		n, e := strconv.Atoi(x)
		if e == nil {
			return n
		}
	}
	return def
}
func obj(w http.ResponseWriter, status int, v interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func fail(w http.ResponseWriter, status int, msg string) {
	obj(w, status, map[string]interface{}{"error": msg})
}
func readJSON(r *http.Request) (map[string]interface{}, error) {
	if !strings.HasPrefix(strings.ToLower(r.Header.Get("Content-Type")), "application/json") {
		return nil, errors.New("请求 Content-Type 必须是 application/json; charset=utf-8")
	}
	b, e := io.ReadAll(io.LimitReader(r.Body, 2_000_001))
	if e != nil {
		return nil, e
	}
	if len(b) == 0 || len(b) > 2_000_000 {
		return nil, errors.New("请求正文长度无效")
	}
	var v map[string]interface{}
	e = json.Unmarshal(b, &v)
	return v, e
}
func validHost(h string) bool {
	h = strings.Trim(h, "[]")
	if a, e := netip.ParseAddr(h); e == nil {
		return a.IsValid()
	}
	if len(h) == 0 || len(h) > 253 {
		return false
	}
	if regexp.MustCompile(`^\d+(\.\d+){3}$`).MatchString(h) {
		return false
	}
	return regexp.MustCompile(`(?i)^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$`).MatchString(h)
}
func validItem(m map[string]interface{}) (string, int, bool) {
	ip := str(m["ip"])
	p := num(m["port"], 0)
	return ip, p, validHost(ip) && p >= 1 && p <= 65535
}
func defaultItem(ip string, port int, raw string, index int) Item {
	return Item{"id": fmt.Sprintf("%s:%d:%d", ip, port, index), "ip": ip, "port": port, "label": "", "route": "", "country": "", "original": raw, "latencyMs": nil, "speedMbps": nil, "speedError": nil, "speedStatus": ""}
}
func parseLine(raw string, index int) []Item {
	raw = strings.TrimSpace(strings.TrimPrefix(raw, "\ufeff"))
	if raw == "" || strings.HasPrefix(raw, "#") || strings.HasPrefix(raw, "//") {
		return nil
	}
	host, port, suffix := "", 443, ""
	patterns := []struct {
		re   *regexp.Regexp
		ipv6 bool
	}{
		{regexp.MustCompile(`^\[([0-9a-fA-F:.%]+)\](?::(\d{1,5}))?(.*)$`), true},
		{regexp.MustCompile(`^((?:\d{1,3}\.){3}\d{1,3})(?::(\d{1,5}))?(.*)$`), false},
		{regexp.MustCompile(`^([A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)(?::(\d{1,5}))?(.*)$`), false},
	}
	matched := false
	for _, p := range patterns {
		m := p.re.FindStringSubmatch(raw)
		if len(m) == 4 {
			host, suffix, matched = m[1], m[3], true
			if m[2] != "" {
				n, e := strconv.Atoi(m[2])
				if e != nil || n < 1 || n > 65535 {
					return nil
				}
				port = n
			}
			break
		}
	}
	if !matched {
		first := strings.FieldsFunc(raw, func(r rune) bool { return r == ',' || r == ';' || r == '|' || r == ' ' || r == '\t' })
		if len(first) == 0 {
			return nil
		}
		if a, e := netip.ParseAddr(first[0]); e == nil && a.Is6() {
			host = a.String()
			suffix = strings.TrimPrefix(raw, first[0])
		} else {
			return nil
		}
	}
	if !validHost(host) || port < 1 || port > 65535 {
		return nil
	}
	fieldsText := strings.TrimSpace(suffix)
	label := fieldsText
	if i := strings.Index(fieldsText, "#"); i >= 0 {
		label = strings.TrimSpace(fieldsText[i+1:])
		fieldsText = strings.TrimSpace(fieldsText[:i])
	}
	fields := regexp.MustCompile(`[\t,;| ]+`).Split(fieldsText, -1)
	route := ""
	for _, token := range fields {
		v := strings.ToUpper(token)
		if routeCodes[v] {
			route = v
			break
		}
	}
	if route == "" {
		if m := regexp.MustCompile(`(?i)(?:^|[^A-Za-z0-9])([A-Z]{3})(?:$|[^A-Za-z0-9])`).FindStringSubmatch(label); len(m) > 1 {
			route = strings.ToUpper(m[1])
		}
	}
	country := ""
	afterHash := raw
	if i := strings.Index(raw, "#"); i >= 0 {
		afterHash = raw[i+1:]
	}
	pipeParts := strings.Split(afterHash, "|")
	if len(pipeParts) >= 2 {
		candidate := strings.ToUpper(strings.TrimSpace(pipeParts[1]))
		if len(candidate) == 2 && !routeCodes[candidate] {
			country = candidate
		}
	}
	if country == "" {
		for _, token := range regexp.MustCompile(`[,;|\s]+`).Split(strings.ToUpper(afterHash), -1) {
			if len(token) == 2 && token[0] >= 'A' && token[0] <= 'Z' && token[1] >= 'A' && token[1] <= 'Z' && !routeCodes[token] && token != "CF" && token != "IP" && token != "MS" && token != "MB" {
				country = token
				break
			}
		}
	}
	item := defaultItem(host, port, raw, index)
	item["label"] = label
	item["route"] = route
	item["country"] = country
	if m := regexp.MustCompile(`(?i)([0-9]+(?:\.[0-9]+)?)\s*ms`).FindStringSubmatch(raw); len(m) > 1 {
		v, _ := strconv.ParseFloat(m[1], 64)
		item["latencyMs"] = v
	}
	if m := regexp.MustCompile(`(?i)([0-9]+(?:\.[0-9]+)?)\s*mbps`).FindStringSubmatch(raw); len(m) > 1 {
		v, _ := strconv.ParseFloat(m[1], 64)
		item["speedMbps"] = v
	}
	return []Item{item}
}
func parseInput(raw string, index int) ([]Item, error) {
	raw = strings.TrimSpace(strings.TrimPrefix(raw, "\ufeff"))
	if raw == "" || strings.HasPrefix(raw, "#") || strings.HasPrefix(raw, "//") {
		return nil, nil
	}
	cidr := raw
	port := 443
	if strings.HasPrefix(cidr, "[") && strings.Contains(cidr, "]") {
		i := strings.Index(cidr, "]")
		rest := cidr[i+1:]
		cidr = cidr[1:i]
		if strings.HasPrefix(rest, ":") {
			port, _ = strconv.Atoi(rest[1:])
		}
	} else if strings.Count(cidr, ":") == 1 && strings.Contains(cidr, "/") {
		p := strings.LastIndex(cidr, ":")
		if n, e := strconv.Atoi(cidr[p+1:]); e == nil {
			port = n
			cidr = cidr[:p]
		}
	}
	if strings.Contains(cidr, "/") {
		p, e := netip.ParsePrefix(cidr)
		if e != nil {
			return nil, fmt.Errorf("CIDR 格式无效：%s", raw)
		}
		p = p.Masked()
		bits := p.Bits()
		total := uint64(1) << uint(min(32, p.Addr().BitLen()-bits))
		if p.Addr().Is6() {
			total = 512
		}
		if total > 512 {
			total = 512
		}
		out := []Item{}
		a := p.Addr()
		for i := uint64(0); i < total; i++ {
			ip := a
			for j := uint64(0); j < i; j++ {
				ip = ip.Next()
			}
			it := defaultItem(ip.String(), port, raw, index*10000+int(i))
			out = append(out, it)
		}
		return out, nil
	}
	return parseLine(raw, index), nil
}
func fetchText(u string) (string, error) {
	if !strings.HasPrefix(strings.ToLower(u), "https://") {
		return "", errors.New("数据源网址必须使用 HTTPS")
	}
	c := &http.Client{Timeout: 20 * time.Second}
	req, _ := http.NewRequest("GET", u, nil)
	req.Header.Set("User-Agent", "PhoneCFIPTool/1.0 Windows")
	r, e := c.Do(req)
	if e != nil {
		return "", e
	}
	defer r.Body.Close()
	if r.StatusCode < 200 || r.StatusCode > 299 {
		return "", fmt.Errorf("数据源返回 HTTP %d", r.StatusCode)
	}
	b, e := io.ReadAll(io.LimitReader(r.Body, 20<<20))
	return string(b), e
}
func detectColo(ip string, port int) string {
	sni := ip
	if a, e := netip.ParseAddr(ip); e == nil {
		if a.Is6() || a.Is4() {
			sni = "www.cloudflare.com"
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	d := net.Dialer{Timeout: 1200 * time.Millisecond}
	raw, e := d.DialContext(ctx, "tcp", net.JoinHostPort(ip, strconv.Itoa(port)))
	if e != nil {
		return ""
	}
	defer raw.Close()
	_ = raw.SetDeadline(time.Now().Add(2500 * time.Millisecond))
	tlsConn := tls.Client(raw, &tls.Config{ServerName: sni, MinVersion: tls.VersionTLS12})
	if e = tlsConn.Handshake(); e != nil {
		return ""
	}
	fmt.Fprintf(tlsConn, "GET /cdn-cgi/trace HTTP/1.1\r\nHost: %s\r\nUser-Agent: PhoneCFIPTool/1.0 Windows\r\nConnection: close\r\nAccept: text/plain\r\n\r\n", sni)
	b, _ := io.ReadAll(io.LimitReader(tlsConn, 12<<10))
	m := regexp.MustCompile(`(?m)^colo=([A-Z]{3})\s*$`).FindSubmatch(b)
	if len(m) > 1 {
		return string(m[1])
	}
	return ""
}

var coloCountries = map[string]string{
	"NRT": "JP",
	"KIX": "JP",
	"HND": "JP",
	"HKG": "HK",
	"SIN": "SG",
	"TPE": "TW",
	"ICN": "KR",
	"LAX": "US",
	"SJC": "US",
	"ORD": "US",
	"IAD": "US",
	"SEA": "US",
	"DFW": "US",
	"ATL": "US",
	"MIA": "US",
	"BOS": "US",
	"DEN": "US",
	"PHX": "US",
	"PDX": "US",
	"SLC": "US",
	"EWR": "US",
	"MSP": "US",
	"MCI": "US",
	"IAH": "US",
	"CLT": "US",
	"DTW": "US",
	"BWI": "US",
	"LAS": "US",
	"SAN": "US",
	"SNA": "US",
	"AUS": "US",
	"RDU": "US",
	"CLE": "US",
	"PIT": "US",
	"CMH": "US",
	"IND": "US",
	"BNA": "US",
	"SAT": "US",
	"OKC": "US",
	"ABQ": "US",
	"BUF": "US",
	"RIC": "US",
	"JAX": "US",
	"ORF": "US",
	"TPA": "US",
	"MCO": "US",
	"FLL": "US",
	"PBI": "US",
	"RSW": "US",
	"CHS": "US",
	"SAV": "US",
	"GSP": "US",
	"PWM": "US",
	"BTV": "US",
	"ALB": "US",
	"SYR": "US",
	"ROC": "US",
	"GRR": "US",
	"FNT": "US",
	"TUL": "US",
	"LIT": "US",
	"SHV": "US",
	"LBB": "US",
	"AMA": "US",
	"MAF": "US",
	"ELP": "US",
	"TUS": "US",
	"YUM": "US",
	"FAT": "US",
	"RNO": "US",
	"BOI": "US",
	"GEG": "US",
	"BIL": "US",
	"FSD": "US",
	"OMA": "US",
	"DSM": "US",
	"CID": "US",
	"PIA": "US",
	"EVV": "US",
	"LEX": "US",
	"SDF": "US",
	"DAY": "US",
	"CAK": "US",
	"ERI": "US",
	"AVP": "US",
	"ITH": "US",
	"MHT": "US",
	"BDL": "US",
	"HVN": "US",
	"ACK": "US",
	"MVY": "US",
	"FRA": "DE",
	"MUC": "DE",
	"DUS": "DE",
	"HAM": "DE",
	"BER": "DE",
	"STR": "DE",
	"NUE": "DE",
	"LEJ": "DE",
	"HAJ": "DE",
	"CGN": "DE",
	"AMS": "NL",
	"LHR": "GB",
	"MAN": "GB",
	"EDI": "GB",
	"GLA": "GB",
	"BHX": "GB",
	"BRS": "GB",
	"CDG": "FR",
	"ORY": "FR",
	"MRS": "FR",
	"LYS": "FR",
	"TLS": "FR",
	"NCE": "FR",
	"BOD": "FR",
	"NTE": "FR",
	"LIL": "FR",
	"MPL": "FR",
	"MEL": "AU",
	"SYD": "AU",
	"BNE": "AU",
	"PER": "AU",
	"ADL": "AU",
	"CBR": "AU",
	"YYZ": "CA",
	"YUL": "CA",
	"YVR": "CA",
	"YOW": "CA",
	"YYC": "CA",
	"YEG": "CA",
	"YHZ": "CA",
	"YWG": "CA",
	"MAD": "ES",
	"BCN": "ES",
	"VLC": "ES",
	"AGP": "ES",
	"BIO": "ES",
	"SVQ": "ES",
	"FCO": "IT",
	"MXP": "IT",
	"LIN": "IT",
	"VCE": "IT",
	"NAP": "IT",
	"BLQ": "IT",
	"ZRH": "CH",
	"GVA": "CH",
	"BSL": "CH",
	"VIE": "AT",
	"BRU": "BE",
	"CPH": "DK",
	"BLL": "DK",
	"ARN": "SE",
	"GOT": "SE",
	"MMX": "SE",
	"OSL": "NO",
	"BGO": "NO",
	"TRD": "NO",
	"HEL": "FI",
	"WAW": "PL",
	"KRK": "PL",
	"KTW": "PL",
	"PRG": "CZ",
	"BRQ": "CZ",
	"BUD": "HU",
	"OTP": "RO",
	"ATH": "GR",
	"SKG": "GR",
	"IST": "TR",
	"SAW": "TR",
	"DXB": "AE",
	"AUH": "AE",
	"JNB": "ZA",
	"CPT": "ZA",
	"DUR": "ZA",
	"GRU": "BR",
	"GIG": "BR",
	"POA": "BR",
	"BSB": "BR",
	"EZE": "AR",
	"COR": "AR",
	"MEX": "MX",
	"GDL": "MX",
	"MTY": "MX",
	"QRO": "MX",
	"DEL": "IN",
	"BOM": "IN",
	"BLR": "IN",
	"MAA": "IN",
	"HYD": "IN",
	"CCU": "IN",
	"BKK": "TH",
	"KUL": "MY",
	"CGK": "ID",
	"MNL": "PH",
	"HAN": "VN",
	"SGN": "VN",
	"DAD": "VN",
	"AKL": "NZ",
	"WLG": "NZ",
	"CHC": "NZ",
}

func countryColo(c string) string { return coloCountries[strings.ToUpper(c)] }
func probe(ip string, port int) (float64, error) {
	start := time.Now()
	conn, e := net.DialTimeout("tcp", net.JoinHostPort(ip, strconv.Itoa(port)), 2500*time.Millisecond)
	if e != nil {
		return 0, e
	}
	conn.Close()
	return float64(time.Since(start).Microseconds()) / 1000, nil
}
func handleList(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	region := strings.ToUpper(q.Get("region"))
	if region == "" {
		region = "JP"
	}
	if _, ok := regions[region]; !ok {
		fail(w, 400, "不支持的地区代码")
		return
	}
	source := strings.TrimSpace(q.Get("source"))
	label := "自定义输入"
	raw := source
	if source == "" {
		label = "https://bestcf.pages.dev/random-region/" + region + "/all.txt"
		var e error
		raw, e = fetchText(label)
		if e != nil {
			fail(w, 502, "获取数据源失败："+e.Error())
			return
		}
	} else if strings.HasPrefix(strings.ToLower(source), "http://") || strings.HasPrefix(strings.ToLower(source), "https://") {
		if !strings.HasPrefix(strings.ToLower(source), "https://") {
			fail(w, 400, "数据源网址必须使用 HTTPS")
			return
		}
		label = source
		var e error
		raw, e = fetchText(source)
		if e != nil {
			fail(w, 502, "获取数据源失败："+e.Error())
			return
		}
	}
	all := []Item{}
	seen := map[string]bool{}
	for i, line := range strings.Split(raw, "\n") {
		items, e := parseInput(line, i)
		if e != nil {
			fail(w, 400, e.Error())
			return
		}
		for _, it := range items {
			k := str(it["ip"]) + ":" + str(it["port"])
			if !seen[k] {
				seen[k] = true
				all = append(all, it)
			}
		}
	}
	// Fill metadata for custom sources when absent; bounded parallel probes.
	if source != "" {
		sem := make(chan struct{}, 32)
		var wg sync.WaitGroup
		for _, it := range all {
			if str(it["route"]) != "" && str(it["country"]) != "" {
				continue
			}
			wg.Add(1)
			go func(it Item) {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()
				colo := detectColo(str(it["ip"]), num(it["port"], 443))
				if str(it["route"]) == "" {
					it["route"] = colo
				}
				if str(it["country"]) == "" {
					it["country"] = countryColo(colo)
				}
			}(it)
		}
		wg.Wait()
	}
	routes := []string{}
	rseen := map[string]bool{}
	for _, it := range all {
		v := str(it["route"])
		if v != "" && !rseen[v] {
			routes = append(routes, v)
			rseen[v] = true
		}
	}
	requested := strings.ToUpper(q.Get("route"))
	if requested == "" {
		requested = "全部"
	}
	items := []Item{}
	for _, it := range all {
		if requested == "全部" || requested == "ALL" || strings.ToUpper(str(it["route"])) == requested {
			items = append(items, it)
		}
	}
	obj(w, 200, map[string]interface{}{"source": label, "region": region, "count": len(items), "total": len(all), "routes": routes, "items": items})
}
func api(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/" && r.Method == "GET" {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(page)
		return
	}
	if r.URL.Path == "/favicon.ico" {
		w.WriteHeader(204)
		return
	}
	if r.URL.Path == "/api/regions" && r.Method == "GET" {
		m := map[string]interface{}{}
		for k, v := range regions {
			m[k] = map[string]string{"name": v}
		}
		obj(w, 200, map[string]interface{}{"regions": m})
		return
	}
	if r.URL.Path == "/api/list" && r.Method == "GET" {
		handleList(w, r)
		return
	}
	if r.URL.Path == "/api/config" {
		if r.Method == "GET" {
			obj(w, 200, readConfig())
			return
		}
		if r.Method == "DELETE" {
			_ = saveConfig(Config{})
			obj(w, 200, map[string]bool{"ok": true})
			return
		}
		if r.Method == "POST" {
			m, e := readJSON(r)
			if e != nil {
				fail(w, 400, e.Error())
				return
			}
			c := Config{strings.TrimRight(str(m["workerUrl"]), "/"), str(m["workerSecret"])}
			if c.WorkerURL != "" && !strings.HasPrefix(c.WorkerURL, "https://") {
				fail(w, 400, "Worker 地址必须使用 HTTPS")
				return
			}
			if e = saveConfig(c); e != nil {
				fail(w, 500, "保存配置失败："+e.Error())
				return
			}
			obj(w, 200, map[string]bool{"ok": true})
			return
		}
	}
	if r.Method != "POST" {
		fail(w, 404, "Not found")
		return
	}
	m, e := readJSON(r)
	if e != nil {
		fail(w, 400, e.Error())
		return
	}
	switch r.URL.Path {
	case "/api/optimize":
		raw, _ := m["items"].([]interface{})
		items := make([]map[string]interface{}, 0, len(raw))
		totalCount := 0
		totalCount = len(raw)
		for _, x := range raw {
			it, ok := x.(map[string]interface{})
			if !ok {
				continue
			}
			ip, p, ok := validItem(it)
			if ok {
				it["ip"] = ip
				it["port"] = p
				items = append(items, it)
			}
		}
		if len(items) == 0 {
			fail(w, 400, "没有有效 IP")
			return
		}
		var wg sync.WaitGroup
		sem := make(chan struct{}, 16)
		for _, it := range items {
			wg.Add(1)
			go func(it map[string]interface{}) {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()
				ms, e := probe(str(it["ip"]), num(it["port"], 443))
				if e != nil {
					it["latencyMs"] = nil
					it["latencyError"] = e.Error()
				} else {
					it["latencyMs"] = ms
					delete(it, "latencyError")
				}
			}(it)
		}
		wg.Wait()
		aliveItems := make([]map[string]interface{}, 0, len(items))
		for _, it := range items {
			if ms, ok := it["latencyMs"].(float64); ok && ms >= 0 {
				it["ok"] = true
				aliveItems = append(aliveItems, it)
			}
		}
		sort.SliceStable(aliveItems, func(i, j int) bool {
			a, aok := items[i]["latencyMs"].(float64)
			b, bok := items[j]["latencyMs"].(float64)
			if aok != bok {
				return aok
			}
			if !aok {
				return false
			}
			return a < b
		})
		obj(w, 200, map[string]interface{}{"count": totalCount, "alive": len(aliveItems), "items": aliveItems})
		return
	case "/api/latency":
		it, _ := m["item"].(map[string]interface{})
		if it == nil {
			fail(w, 400, "IP 或端口无效")
			return
		}
		ip, p, ok := validItem(it)
		if !ok {
			fail(w, 400, "IP 或端口无效")
			return
		}
		ms, e := probe(ip, p)
		if e != nil {
			obj(w, 200, map[string]interface{}{"ok": false, "error": e.Error(), "latencyMs": nil})
			return
		}
		obj(w, 200, map[string]interface{}{"ok": true, "latencyMs": ms})
		return
	case "/api/speed":
		it, _ := m["item"].(map[string]interface{})
		if it == nil {
			fail(w, 400, "IP 或端口无效")
			return
		}
		ip, p, ok := validItem(it)
		if !ok {
			fail(w, 400, "IP 或端口无效")
			return
		}
		speedStream(w, r, ip, p)
		return
	case "/api/upload":
		upload(w, r, m)
		return
	}
	fail(w, 404, "Not found")
}
func speedStream(w http.ResponseWriter, r *http.Request, ip string, port int) {
	fl, ok := w.(http.Flusher)
	if !ok {
		fail(w, 500, "服务器不支持流式输出")
		return
	}
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache, no-transform")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(200)
	emit := func(v interface{}) bool {
		b, _ := json.Marshal(v)
		if _, e := fmt.Fprintf(w, "data: %s\n\n", b); e != nil {
			return false
		}
		fl.Flush()
		return true
	}
	start := time.Now()
	var received int64
	d := net.Dialer{Timeout: 5 * time.Second}
	raw, e := d.DialContext(r.Context(), "tcp", net.JoinHostPort(ip, strconv.Itoa(port)))
	if e != nil {
		emit(map[string]interface{}{"type": "done", "ok": false, "error": e.Error(), "receivedBytes": 0, "elapsedMs": time.Since(start).Milliseconds(), "avgMbps": 0})
		return
	}
	defer raw.Close()
	_ = raw.SetDeadline(time.Now().Add(12 * time.Second))
	conn := tls.Client(raw, &tls.Config{ServerName: "speed.cloudflare.com", MinVersion: tls.VersionTLS12})
	if e = conn.Handshake(); e != nil {
		emit(map[string]interface{}{"type": "done", "ok": false, "error": e.Error(), "receivedBytes": 0, "elapsedMs": time.Since(start).Milliseconds(), "avgMbps": 0})
		return
	}
	_, _ = fmt.Fprintf(conn, "GET /__down?bytes=10000000 HTTP/1.1\r\nHost: speed.cloudflare.com\r\nUser-Agent: PhoneCFIPTool/1.0 Windows\r\nConnection: close\r\nAccept: */*\r\n\r\n")
	br := bufio.NewReader(conn)
	resp, readErr := http.ReadResponse(br, &http.Request{Method: http.MethodGet})
	if readErr != nil {
		emit(map[string]interface{}{"type": "done", "ok": false, "error": "测速端点无响应：" + readErr.Error(), "receivedBytes": 0, "elapsedMs": time.Since(start).Milliseconds(), "avgMbps": 0})
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		emit(map[string]interface{}{"type": "done", "ok": false, "error": fmt.Sprintf("测速端点返回 HTTP %d", resp.StatusCode), "receivedBytes": 0, "elapsedMs": time.Since(start).Milliseconds(), "avgMbps": 0})
		return
	}
	buf := make([]byte, 16*1024)
	last := time.Now()
	lastBytes := int64(0)
	began := time.Now()
	for received < 10_000_000 && time.Since(began) < 10*time.Second {
		if r.Context().Err() != nil {
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, e := resp.Body.Read(buf)
		if n > 0 {
			received += int64(n)
		}
		now := time.Now()
		elapsed := now.Sub(start).Milliseconds()
		if now.Sub(last) >= 250*time.Millisecond {
			delta := received - lastBytes
			emit(map[string]interface{}{"type": "progress", "receivedBytes": received, "currentMbps": float64(delta*8) / float64(max(int64(1), now.Sub(last).Milliseconds())) / 1000, "avgMbps": float64(received*8) / float64(max(int64(1), elapsed)) / 1000, "elapsedMs": elapsed})
			last = now
			lastBytes = received
		}
		if e != nil {
			break
		}
	}
	elapsed := max(int64(1), time.Since(start).Milliseconds())
	emit(map[string]interface{}{"type": "done", "ok": received > 0, "error": func() interface{} {
		if received > 0 {
			return nil
		}
		return "未接收到测速数据"
	}(), "receivedBytes": received, "elapsedMs": elapsed, "avgMbps": float64(received*8) / float64(elapsed) / 1000})
}
func normalizeSub(line string) string { return strings.TrimSpace(line) }
func upload(w http.ResponseWriter, r *http.Request, m map[string]interface{}) {
	c := readConfig()
	if c.WorkerURL == "" || c.WorkerSecret == "" {
		fail(w, 400, "请先在右上角菜单配置 Worker 地址和密钥")
		return
	}
	text := str(m["text"])
	mode := strings.ToLower(str(m["mode"]))
	if mode == "" {
		mode = "overwrite"
	}
	if mode != "overwrite" && mode != "append" {
		fail(w, 400, "不支持的上传模式")
		return
	}
	lines := []string{}
	for _, line := range strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n") {
		line = strings.TrimSpace(line)
		if line != "" {
			lines = append(lines, line)
		}
	}
	if len(lines) == 0 {
		fail(w, 400, "请至少选择 1 个 IP")
		return
	}
	if strings.Contains(text, "\uFFFD") {
		fail(w, 400, "上传文本已包含乱码替换字符，已取消更新")
		return
	}
	out := lines
	added := len(lines)
	updated := 0
	client := &http.Client{Timeout: 15 * time.Second}
	if mode == "append" {
		added = 0
		req, _ := http.NewRequest("GET", c.WorkerURL+"/", nil)
		req.Header.Set("Authorization", "Bearer "+c.WorkerSecret)
		resp, e := client.Do(req)
		if e != nil {
			fail(w, 502, "读取服务器现有订阅失败："+e.Error())
			return
		}
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 5<<20))
		resp.Body.Close()
		if resp.StatusCode < 200 || resp.StatusCode > 299 {
			fail(w, 502, fmt.Sprintf("读取服务器现有订阅失败：HTTP %d，未修改服务器内容", resp.StatusCode))
			return
		}
		existing := map[string]string{}
		order := []string{}
		for _, line := range strings.Split(string(body), "\n") {
			line = strings.TrimSpace(line)
			if line == "" {
				continue
			}
			key := strings.TrimSpace(strings.SplitN(line, "#", 2)[0])
			if _, ok := existing[key]; !ok {
				existing[key] = line
				order = append(order, key)
			}
		}
		for _, line := range lines {
			key := strings.TrimSpace(strings.SplitN(line, "#", 2)[0])
			if _, ok := existing[key]; !ok {
				added++
				order = append(order, key)
			} else if existing[key] != line {
				updated++
			}
			existing[key] = line
		}
		out = []string{}
		for _, k := range order {
			out = append(out, existing[k])
		}
	}
	valid := regexp.MustCompile(`^(\[[0-9a-fA-F:.]+\]|(?:\d{1,3}\.){3}\d{1,3}|[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*):\d{1,5}(?:#.*)?$`)
	for _, line := range out {
		if !valid.MatchString(line) {
			fail(w, 400, "请提供格式正确的 IPv4/域名:端口或 [IPv6]:端口记录；旧订阅未修改。")
			return
		}
	}
	payload, _ := json.Marshal(map[string]string{"text": strings.Join(out, "\n") + "\n"})
	req, _ := http.NewRequest("POST", c.WorkerURL+"/update", strings.NewReader(string(payload)))
	req.Header.Set("Authorization", "Bearer "+c.WorkerSecret)
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	resp, e := client.Do(req)
	if e != nil {
		fail(w, 502, "Worker 更新失败："+e.Error())
		return
	}
	detail, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		obj(w, 502, map[string]interface{}{"error": fmt.Sprintf("Worker 更新失败：HTTP %d", resp.StatusCode), "detail": string(detail)})
		return
	}
	if mode != "append" {
		added = len(out)
	}
	obj(w, 200, map[string]interface{}{"ok": true, "added": added, "updated": updated, "count": len(out), "message": map[bool]string{true: "订阅已追加", false: "订阅已覆盖"}[mode == "append"], "detail": string(detail)})
}
const appURL = "http://127.0.0.1:8788"

func openBrowser() {
	if runtime.GOOS == "windows" {
		_ = exec.Command("rundll32", "url.dll,FileProtocolHandler", appURL).Start()
	} else {
		_ = exec.Command("xdg-open", appURL).Start()
	}
}

// alreadyRunning checks whether the service on the fixed local port is this app.
// This lets repeated launches reopen the existing UI instead of starting a second server.
func alreadyRunning() bool {
	client := &http.Client{Timeout: 700 * time.Millisecond}
	resp, err := client.Get(appURL + "/api/regions")
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return false
	}
	var data map[string]interface{}
	if json.NewDecoder(io.LimitReader(resp.Body, 64*1024)).Decode(&data) != nil {
		return false
	}
	_, ok := data["regions"]
	return ok
}

func main() {
	if alreadyRunning() {
		openBrowser()
		return
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { api(w, r) })
	mux.HandleFunc("/api/", api)
	srv := &http.Server{Addr: "127.0.0.1:8788", Handler: mux, ReadHeaderTimeout: 10 * time.Second}

	// Bind synchronously so a port conflict is detected before opening a browser.
	listener, err := net.Listen("tcp", srv.Addr)
	if err != nil {
		if alreadyRunning() {
			openBrowser()
			return
		}
		log.Printf("无法启动本地服务（127.0.0.1:8788）：%v", err)
		return
	}

	go func() {
		time.Sleep(300 * time.Millisecond)
		openBrowser()
	}()
	log.Println("CF 优选助手已启动：" + appURL)
	if err := srv.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Printf("本地服务已停止：%v", err)
	}
}
