package main

import (
	"flag"
	"github.com/docker/go-plugins-helpers/volume"
	"io/ioutil"
	"os"
	"strconv"
	"syscall"
)

func main() {
	lowerRootDir := flag.String("cacheLowerDir", "/cache", "root location of lower dir")
	upperRootDir := flag.String("cacheUpperDir", "/mnt/cache-upper", "root location of upper dir")
	workRootDir := flag.String("cacheWorkDir", "/mnt/cache-work", "root location of work dir")
	mergedRootDir := flag.String("cacheMergedDir", "/mnt/cache-merged", "root location of merged dir")
	WithLock("/var/run/cache-driver.pid", func() {
		flag.Parse()
		driver := newCacheDriver(lowerRootDir, upperRootDir, workRootDir, mergedRootDir)
		handler := volume.NewHandler(driver)
		err := handler.ServeUnix("root", driver.name)
		if err != nil {
			os.Exit(1)
		}
	})
}
func WithLock(pidFileName string, f func()) {
	pidFile, err := os.Create(pidFileName)
	if err != nil {
		os.Exit(1)
	}
	defer pidFile.Close()
	err = ioutil.WriteFile(pidFileName, []byte(strconv.Itoa(os.Getpid())), 0755)
	if err != nil {
		os.Exit(1)
	}

	err = syscall.Flock(int(pidFile.Fd()), syscall.LOCK_EX)
	if err != nil {
		os.Exit(1)
	}
	defer syscall.Flock(int(pidFile.Fd()), syscall.LOCK_UN)
	f()

}
