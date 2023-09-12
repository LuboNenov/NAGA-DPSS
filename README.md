# NAGA - COnfidential Byzantine ReplicAtion SMR library

NAGA is a fully-featured state machine replication library that guarantees the confidentiality of the data. 
Confidentiality is ensured by integrating a secret sharing mechanism into the 
modified [BFT-SMaRt](https://github.com/bft-smart/library) library, a fully-featured replication library without 
confidentiality guarantees. You can find the modified version of the BFT-SMaRt library [here](https://github.com/rvassantlal/library).

## Limitations
This library is a proof-of-concept implementation and not a production-ready implementation. 
Therefore, before using it, consider the following limitations:
* The periodic execution of the resharing protocol is disabled. It can be activated but requires hardcoding 
the number of shares to reshare;
* The constant commitment scheme, i.e., Kate et al.'s protocol, was not tested with recent changes;
* Recovery and resharing while changing the leader was not tested.

## Requirements
The library is primarily implemented in Java and currently uses Gradle to compile, package, and 
deploy compiled code for local testing. Nevertheless, we use C to implement the constant commitment scheme 
functions and call those functions in Java through Java Native Interface.

The current library was tested using Java 11.0.13.

## Compilation and Packaging
First, clone this repository. Now inside the folder, follow 
the following instructions depending on the intended result.

There are two ways to compile and package the NAGA library:
* Compile and package the library: Execute `./gradlew installDist`. The required jar files and default 
configurations files will be available inside the `build/install/NAGA` folder.
* Compile and package to locally test the library: Execute `./gradlew localDeploy`. The execution of Gradle 
task `localDeploy` will create the folder `build/local` containing `nServers` folders `rep*` and `nClients` 
folders `cli*` (you can change these parameters in the `build.gradle` file). Each server and client folder 
will have the required files to run COBRA demos.

***Compiling C code***

The constant commitment scheme is implemented in C using [`relic` library](https://github.com/relic-toolkit/relic). 
Execute the following commands inside `pairing` folder to compile the `relic` library and C code:
1. Compile the `relic` library by executing `./build_relic.sh`;
2. Compile the C code by executing `./build.sh <path to java folder>`.


## Usage
Since NAGA extends the BFT-SMaRt library, first configure BFT-SMaRt following instructions presented in 
its [repository](https://github.com/bft-smart/library). Then configure NAGA's behaviour by modifying the 
`config/cobra.config` file.


**TIP:** Reconfigure the system before compiling and packaging. This way, you don't have to configure multiple replicas.

**NOTE:** Following commands considers the Linux operating system. For the Windows operating system, 
use script `run.cmd` instead of `./smartrun.sh`.

***Running the map demo (4 replicas tolerating 1 fault):***

Execute the following commands across four different server consoles from within 
the folders `build/local/rep*`:
```
build/local/rep0$./smartrun.sh confidential.demo.map.server.Server 0
build/local/rep1$./smartrun.sh confidential.demo.map.server.Server 1
build/local/rep2$./smartrun.sh confidential.demo.map.server.Server 2
build/local/rep3$./smartrun.sh confidential.demo.map.server.Server 3
```

Once all replicas are ready, the client can be launched by executing the following command in 
directory `build/local/cli0/`:
```
build/local/cli0$./smartrun.sh confidential.demo.map.client.Client 100
```

***Running throughput and latency experiment:***

After compiling and packaging, copy the content of the `NAGA/build/install/COBRA` folder into
different locations/servers. Next, we present an example of running a system with four replicas
tolerating one fault.

Execute the following commands across four different server consoles:
```
./smartrun.sh confidential.benchmark.ThroughputLatencyKVStoreServer 0
./smartrun.sh confidential.benchmark.ThroughputLatencyKVStoreServer 1
./smartrun.sh confidential.benchmark.ThroughputLatencyKVStoreServer 2
./smartrun.sh confidential.benchmark.ThroughputLatencyKVStoreServer 3
```

Once all replicas are ready, you can launch clients by executing the following command:
```
./smartrun.sh confidential.benchmark.PreComputedKVStoreClient <initial client id> <num clients> <number of ops> <request size> <write?> <precomputed?> <measurement leader?>
```
where:
* `<initial client id>` - the initial client id, e.g, 100;
* `<num clients>` - the number clients each execution of command will create, e.g., 20;
* `<number of ops>` - the number of requests each client will send, e.g., 10000;
* `<request size>` - the size in byte of each request, e.g., 1024;
* `<write?>` - requests are of write or read type? E.g., true;
* `<precompute?>` - are the requests precompute before sending to servers or are created on fly? E.g., true;
* `<measurement leader?>` - will this client print the latencies? E.g., true.

***Interpreting the throughput and latency results***

When clients continuously send the requests, servers will print the throughput information
every two seconds.
When a client finishes sending the requests, it will print a string containing space-separated
latencies of each request in nanoseconds. For example, you can use this result to compute average latency.

## Changes to BFT-SMaRt
Following are the relevant modifications done in BFT-SMaRt:
* Invoking ordered and unordered operations with distinct public and private parts of requests;
* Temporarily storing private state (i.e., shares) in `ClientData` during the consensus execution;
* Checking proposed value during the consensus execution;
* Added a metadata field inside `MessageContext` and `TOMMessage`;
* Added a reconfiguration listener.

## References

We empirically showed that the NAGA library improves the state-of-the-art protocol 
[VSSR](https://dl.acm.org/doi/10.1145/3319535.3354207) in recovery and the state-of-the-art protocol 
[MPSS](https://dl.acm.org/doi/10.1145/1880022.1880028) in resharing. The prototype implementation of VSSR and MPSS 
can be found [here](https://github.com/rvassantlal/VSSR) and [here](https://github.com/rvassantlal/MPSS), respectively.
