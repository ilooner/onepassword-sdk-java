# Third-party notices

The wire schema, model definitions, operation mappings, and native ABI bindings
are derived from the following 1Password SDKs:

- [Go SDK](https://github.com/1Password/onepassword-sdk-go), commit `5866f43111ffeee5952e43a13da1aafef98200c8`.
- [JavaScript SDK](https://github.com/1Password/onepassword-sdk-js), commit `ec1e4625abca0a883ae31ceb3e17b71771b244bb`.
- [Python SDK](https://github.com/1Password/onepassword-sdk-python), commit `5de999bb10a7119d1393c3c8e37595453ce3213c`, version 0.4.2, native build `0040201`.

The optional platform JAR contains an unmodified native library distributed in
the Python SDK. `gradle/native-libraries.json` records its immutable source and
SHA-256 digest. The downloaded library implements the cryptography, network
protocol, authentication, and password generator; this project supplies Java
bindings to it. The desktop transport loads the user's installed 1Password app.

The following upstream license applies to the derived material and the native
libraries distributed in the Python SDK:

```text
MIT License

Copyright (c) 2024 1Password

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Java dependencies are resolved separately by Gradle: Jackson (Apache-2.0),
JNA (Apache-2.0 or LGPL-2.1-or-later), and JUnit (EPL-2.0, tests only).
The Gradle wrapper is distributed under Apache-2.0.
