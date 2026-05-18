


# 目标
将 XWiki 的 XAR 导出为 Markdown，并可选同步到 Confluence（含附件）。

## 数据目录
- 输入目录示例：`xwiki/`
- 数据不允许修改（工具仅读取）
- 目录是 放的导出的 xar 文件

## 使用
### 1) 编译
需要 JDK 11+（Confluence 同步使用 Java HttpClient）。
`javac -encoding UTF-8 XwikiXarExporter.java`

### 2) 导出（XAR → Markdown）
- 默认输出目录：`out`
- 示例（输入为目录：会递归扫描 `.xar` 文件）：
  - `java XwikiXarExporter --input "xwiki" --output out`
- 可选参数：
  - `--format markdown|raw`（默认 `markdown`，`raw` 表示输出原始 XWiki 语法）
  - `--include-preferences`（默认不导出 `WebPreferences.xml`）
  - `--only-entry <entry>`（只导出单个页面/条目，用于快速测试）

### 单页测试用例
1) 找到目标页面在 XAR 内的条目名（一般是 `.../WebHome.xml`）：
   - `jar tf <your.xar> | grep WebHome.xml | head`
2) 只导出该条目：
   - `java XwikiXarExporter --input <your.xar> --output out --only-entry "<Space>/<Page>/WebHome.xml"`
3) 查看输出：
   - `out/<xar文件名去后缀>/<Space>/<Page>/index.md`

### 3) 同步到 Confluence（可选）
同时提供以下参数时启用同步：
- `--confluence-url <url>`
- `--space <space>`
- `--user <user>`
- `--password <apiToken>`
- `--dry-run`（可选：不发起 HTTP 请求，只打印将要创建/更新/上传的内容）

示例：
`java XwikiXarExporter --input "xwiki" --output out --confluence-url <url> --space <space> --ancestor <parentId> --user <user> --password <token>`

说明：
- `--ancestor` 可选；不传则创建在 Space 根目录

### 只用已有 out 目录上传（不重新解析 XAR）
适用于：已经跑过一次导出，`out/...` 下有 `pages.csv` 和每页的 `index.md`/附件文件。

示例（上传单个导出目录）：
`java XwikiXarExporter --upload-from out/<xar文件名去后缀> --confluence-url <url> --space <space> --user <user> --password <token>`

示例（上传 out 根目录：会自动扫描其下每个包含 pages.csv 的子目录）：
`java XwikiXarExporter --upload-from out --confluence-url <url> --space <space> --user <user> --password <token>`

### 4) 附件/图片处理
- XAR 可能包含附件：附件内容通常在页面 XML 的 `<attachment><content>` 中以 base64 内嵌，`jar tf` 不一定能看到独立的 `.png/.docx` 文件
- 工具会自动从页面 XML 提取内嵌附件到页面目录，并在上传页面后同步上传到 Confluence
- 若 Markdown 中引用了 `image:xxx.png` / `attach:xxx.ext` 但 XAR 未内嵌该文件，需要额外提供 XWiki 的附件目录：
  - `java XwikiXarExporter --input "xwiki" --output out --attachments-dir <xwiki-attachments-root>`

附件目录结构约定：
- `<attachments-dir>/<space>/<page>/<filename.ext>`

## Confluence 配置
- `--confluence-url` 形如：`https://your-confluence.example.com`
 
