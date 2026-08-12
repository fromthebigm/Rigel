# Web inspection

Rigel's native WKWebView instances are created with inspection enabled on macOS
versions that expose Apple's inspectable-WKWebView API.

## Full Apple Web Inspector

1. In Safari, open **Settings → Advanced**.
2. Enable **Show features for web developers**.
3. Keep the target page open in Rigel.
4. In Safari, choose **Develop → your Mac → the Rigel page**.

Depending on the macOS/WebKit version, right-clicking a page in Rigel may also
show **Inspect Element**.

## Built-in JavaScript console

Use **Tools → JavaScript Console…** or press **Cmd+Option+J**. Code runs in the
active tab, and the value of the final expression is displayed.

Example AV1 check:

```javascript
document.createElement('video').canPlayType(
  'video/mp4; codecs="av01.0.05M.08"'
)
```
