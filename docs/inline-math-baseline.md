# 行内公式：基线对齐

状态：已改为原生 TextView 段落和 ReplacementSpan，待真机视觉验收。

## 现象

句子里的行内公式不坐在文字基线上。`\displaystyle`、`\dfrac` 把积分号和分式拉高之后，公式浮在字行中间。

块级 `$$...$$` 独占一块，没有这个问题。

## 原因

旧版 `MarkdownText.kt` 的 `RichMarkdownText` 把一段拆成自定义 `InlineBaselineFlow`：

- 文字是独立的 `Text`，字形落在基线上。
- 公式是独立的 `LatexView`，内部是 `ImageView` + `JLatexMathDrawable`。

旧版 `FlowRow` 没有使用子项基线对齐，公式和文字按各自控件的位置布局。公式越高，和文字基线的偏差越明显；当前已改为原生段落排版。

块级公式字号保持不变；普通行内公式现在按正文字号和屏幕 density 计算：

| 场景 | `textSize` |
|---|---|
| 块级 `display && !inline` | 52f |
| 行内且 `displaySize` | 正文字号 × 1.15 |
| 普通行内 | 正文字号 |

`displaySize` 在 `splitInlineMath` 里为真的条件：`$$...$$`，或源码含 `\displaystyle` / `\dfrac`。这个放大是预期行为，不改。

原先的 `inline` 使用 `ALIGN_LEFT`、padding=2 和 `LatexBaseline` alignment line；现在行内公式由 `NativeLatexSpan` 直接绘制，使用零额外 padding，避免 drawable 隐含边距把行高撑大。

## 可用的基线

`JLatexMathDrawable.icon()` 返回 `org.scilab.forge.jlatexmath.TeXIcon`（jlatexmath-android 0.2.0 runtime）：

- `getIconHeight()` / `getIconDepth()`：公式盒中基线以上、以下的两段；`getIconHeight()` 不是基线位置
- `getTrueIconHeight()` / `getTrueIconDepth()`：未取整的高度、深度
- `getBaseLine()`：基线比例
- `getBox().getHeight()` / `getDepth()`：公式盒本身，不含 drawable padding

旧版行内 padding 是 2px。基线距图片顶端：

```text
baselineFromTop = paddingTop + iconHeight - iconDepth
imageHeight     = paddingTop + iconHeight + iconDepth + paddingBottom
```

优先用 `getIconHeight()` / `getIconDepth()`，因为它们已经含 insets。实现时用一条已知公式（例如 `x`）对一下 `getBaseLine()` 与 `iconHeight / (iconHeight + iconDepth)`，两者不一致就以 height/depth 为准。

## 已实现的改法

本轮只动行内。块级 `LatexBlock` 仍居中、52f、可横向滚动。

1. 行内内容改为一个原生 `TextView` 段落，由 Android 统一处理中文、标点和公式之间的换行。
2. 公式改为 `NativeLatexSpan`，基线使用 `insets.top + trueIconHeight - trueIconDepth`，并同步扩展 ascent/descent，避免公式上浮和多算行高。
3. 普通行内公式字号使用 `TextUnit.toPx()`，跟随 density 和系统字体缩放；`displaystyle` / `\dfrac` 保留 1.15 倍放大。
4. 行内公式过宽时按容器宽度同比缩放；块级 `$$` 行为不变。

不把行内公式再降回 40f，也不把 `\displaystyle` 从源码里剥掉。`LatexRender.kt` 那条纯文本降级路径保持原样，它本来就会去掉 `\displaystyle`。

## 不做

- 不改 `splitInlineMath` 的 `displaySize` 判定。
- 不改块级 `$$` 的 52f。
- 不把公式嵌进 `InlineTextContent`。当前使用原生 `TextView` + `ReplacementSpan`，让系统文本排版直接处理公式占位符。
- 不处理「行内公式跨多行」的折行。超宽走横向滚动。

## 验收

同一段正文里并排看：

```text
当 $x>0$ 时，$\int_{0}^{1}\frac{1}{1+x^{2}}\,dx=\frac{\pi}{4}$。
当 $x>0$ 时，$\displaystyle\int_{0}^{1}\dfrac{1}{1+x^{2}}\,dx=\dfrac{\pi}{4}$。
```

- 普通行内：等号、`x` 与周围汉字/字母的基线一致，公式不被压小。
- `\displaystyle` / `\dfrac`：积分号和分式明显高于文字，等号仍与文字基线对齐，字号为正文的 1.15 倍。
- 块级 `$$\frac{a}{b}$$`：仍独占一行，52f，外观与现在相同。
- jlatexmath 解析失败：仍回退成源码文本，不留空白。
