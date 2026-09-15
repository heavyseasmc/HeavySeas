# art/ —— 美术资源

86 件 SVG：卡面插画 67 · 图标 13 · 牌背 3 · LOD 示例 3，外加一份字体配置。
全部为本项目原创，许可 CC BY-SA 4.0，见根目录 `LICENSE-ASSETS`。

```
art/
  cards/       67 张卡面：物资 18 · 角色 8 · 天候 10 · 航海 31
  icons/       13 个图标，64×64 母版
  backs/       3 张牌背：通用 · 秘密 · 物资
  lod/         同一张卡的三档 LOD 示例
  fonts.json   字体配置
```

## 命名

```
provision.<id>.svg    water · medical_kit · bait_bucket · ration · rum · compass
                      life_preserver · parasol · flare_gun · fish_spear · knife
                      flail · oar · cash · jewelry · fine_art_2 · fine_art_3a · fine_art_3b
character.<id>.svg    jeweler · collector · captain · mate · hostess · sailor · doctor · kid
weather.<id>.svg      becalmed · clear_skies · dense_fog · gale · huge_wave
                      rain · scorching_heat · storm · sunday · sweltering
nav.<id>.svg          nav_00 … nav_30
```

航海牌没有名字，只有编号 —— 它与 `data/navigation` 共用这一套 id，`<title>` 末段印的就是 id。
牌面上那句「医生落海」是**由数据现算的摘要**，不是给它起的专名：起了名就有了第二个真相源。

角色的文件名用**职业**，不用角色的名字。`data/roster` 与本目录共用这一套 id，
中间不需要映射表；名字（珀尔、莫罗……）只是显示名，改名不动主键。

卡面顶栏印的也是职业：珠宝商 · 收藏家 · 船长 · 大副 · 陪酒女 · 水手 · 医生 · 小孩。

三张名画是三个独立 id（2 分一张、3 分两张），不是一个 id 带数量。

## 字体不写死

卡面 SVG 里没有任何写死的字体名，只有两个 CSS 变量，覆盖它们即可换字体：

```css
svg { --cjk: 'KingHwa_OldSong','Huiwen-MinchoGBK','Noto Serif SC',serif;
      --latin: 'Old Standard TT','EB Garamond',serif; }
```

| 字体 | 角色 | 随包分发 |
| --- | --- | --- |
| 汇文明朝体 GBK | 默认 | **是** |
| 思源宋体 | 缺字兜底 | 是 |
| 京華老宋體 | 设计基准 | **否** —— 其授权为保留全部权利，需用户自备 |

卡面按京華老宋體设计，但**不随包分发**：该字体的授权不允许再分发。
包内默认使用汇文明朝体 GBK（SIL OFL 1.1），两者同为宋/明朝体，替换后版面不跑版。
细节见 `fonts.json`。

## LOD

同一份 SVG 按显示尺寸分三档，靠 class 控制。**小尺寸下是整条移除元素，不是缩小字号**
—— 9px 的规则文本缩到 29% 是 2.6px，那不是小字，是噪点。

| class | 含义 |
| --- | --- |
| `lod1-hide` | LOD1 起移除（规则文本） |
| `lod2-hide` | LOD2 起移除（框线、印章、类别条、纸面纹理） |
| `lod01-hide` | 仅 LOD2 显示（放大的牌名） |

渲染整张母版时把 `.lod01-hide` 设为 `display:none`，否则牌名会出现两次。

| 档 | 适用尺寸 | 内容 |
| --- | --- | --- |
| LOD0 | ≥ 240px | 全部 |
| LOD1 | 120–240px | 去规则文本 |
| LOD2 | < 120px | 只留插画 + 放大牌名 |

## 图标

64×64 母版，实际使用尺寸 16px（MC GUI 1×）。单色 + 一个强调色，粗线、无细节。

细长的两个（船桨、刃）是**实心填充**而非描边：16px 下 3.4 的描边只剩 0.85 像素，
描边画法在这个尺寸上必然消失。改动图标时这条要守住。

航海牌图示与玩家标记共用同一张图 —— 同一个意思在两处用两套图会让人怀疑不是一回事。

## 修改

SVG 是手写的矢量源文件，可直接编辑 —— 改一张卡、换一处颜色、挪一个元素，
都不需要别的工具。

❗**航海牌是唯一「内容即插画」的一类**：其余三类各有一个可画的主角（一只桶、一个人、一场暴风雨），
而航海牌的主角是「今天谁下水」—— 一份名单。所以它的画面就是那份名单：一条水线，
点名的那个人站在水里（用的就是角色卡那副胸像），不点名就放艇或酒瓶。浪的高低由这一天动了多少人决定，
不是随手给每张来点变化。

生成它们的 Python 管线**不在本仓库、也不会迁进来**。这不影响你使用或修改：
SVG 本身就是首选的修改形式。但**整套改版**（调色板、版面坐标、字号一次性统一变更）
另当别论 —— 那些参数集中在一个样式模块里，逐文件去改 86 个 SVG 一定会漂移，
这种改动请联系维护者。
