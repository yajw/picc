# Example usage

options:
1. `-k`: api keys, seperated by `,`
2. `-c`: thread pool size
3. `-i`: input path
4. `-o`: output path
5. `-C`: write target image to yyyy/mm subfolder, the date uses image capture date
```text

> java -jar picc-1.0-SNAPSHOT.jar -i ~/pics/ -o ~/compressed/ -k key1,key2 -c 20 -C true
done! 12 MB => 4 MB
```
