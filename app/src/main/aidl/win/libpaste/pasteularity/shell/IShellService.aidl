package win.libpaste.pasteularity.shell;

interface IShellService {
    void destroy() = 16777114;

    ParcelFileDescriptor exec(String command) = 1;
}
