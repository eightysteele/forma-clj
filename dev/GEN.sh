rm -rf ../src/jvm/gen-java
thrift -o "../src/jvm" -r -gen java forma.thrift
