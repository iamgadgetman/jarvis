# Native libraries carried by jarvis-core

`linux-x86_64/libgomp.so.1` and `linux-aarch64/libgomp.so.1` are GCC's
OpenMP runtime, `libgomp`, which the Linux build of whisper.cpp's math
library (`libggml.so` inside whisper-jni) is linked against. A full
distribution has it; a slim container image usually does not, and the
plugin then fails with `libgomp.so.1: cannot open shared object file`.
`NativeSupport` loads the copy here when the system has none.

They are the copies shipped inside the scikit-learn 1.5.2 manylinux2014
wheels for x86_64 and aarch64 (built against glibc 2.17, so they load on
any Linux of the last decade), with the SONAME set back to `libgomp.so.1`
from the hashed name auditwheel gives it. libgomp is part of GCC and is
licensed under the GNU GPL version 3 with the GCC Runtime Library
Exception, which permits its distribution with a program of any licence:
<https://www.gnu.org/licenses/gcc-exception-3.1.html>. The source is GCC's
`libgomp/` directory: <https://gcc.gnu.org/git/?p=gcc.git;a=tree;f=libgomp>.

## How the fallback was verified

On a machine that has libgomp, the fallback never runs, so a unit test
cannot show it working. It was checked by hand in a private mount
namespace (`unshare -m`) with an almost empty directory bound over
`/usr/lib/x86_64-linux-gnu`, holding only glibc's own libraries and
libstdc++, as a slim image would. There, `WhisperJNI.loadLibrary()` fails
with `libgomp.so.1: cannot open shared object file`, the error the first
Paper server reported, and `NativeSupport.loadWhisper` loads whisper on
the bundled copy and `getSystemInfo()` answers. Repeat that after
replacing either file.
