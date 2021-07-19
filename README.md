# async-range-downloader
Utilizes the Range HTTP header to download a file with multiple connections in parallel, bypassing cheap bandwidth throttling

The in parallel running workers are lightweight coroutines built on ktor's HttpClient and coroutine wrappers around java.nio 

### Usage
```
Usage: ard [options...] url [file]
Arguments:
    url -> URL of file to download { String }
    file -> Path of the output file (optional) { String }
Options:
    --workers, -w [10] -> Number of async workers { Int }
    --delay, -d [1] -> Delay between opening requests in seconds { Int }
    --help, -h -> Usage info
```

### TODO
- No error handling whatsoever at this time, should at least care for response codes
- Adding options for custom headers, cookies, or even auth
