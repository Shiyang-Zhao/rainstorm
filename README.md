# RainStorm Stream-Processing Framework

## 1. Design

We implemented a distributed stream-processing framework called Rainstorm for real-time data processing. The RainStorm consists of three parts. Leader-Worker Model, a fault-tolerant leader coordinates task scheduling, failure handling, and exactly-once delivery semantics. Workers execute user-defined operators, process streams, and manage logs for fault recovery. Here are the core features:

## 2. Get Started

Run Gradle build

```groovy
./gradlew clean build
```

Start the RainStorm introducer and node

```groovy
./gradlew runRainStormIntroducer
./gradlew runRainStormNode
```

To invoke the RainStorm:

```
rainstorm Op1.java Op2.java input_1000.txt output_1000.txt [No Outlet] [Warning] 3
```

- `rainstorm`: specifier to start the rainstorm application
- `Op1`: the first operator
- `Op2`: the second operator
- `input`: a file to be processed in hydfs remote side
- `output`: the output file
- `pattern1`
- `pattern2`
- `num_tasks`:  how many task execute

## 3. Performance

We selected 2 dataset, one contains 1000 lines and the other contains 3000 lines. We tested simple (app-1) and complex (app-2) on Spark and RainStorm and plot 4 figures.1. Spark vs RainStorm with 1000 lines, using a simple operator:![img](https://lh7-rt.googleusercontent.com/docsz/AD_4nXe2SatZPvx6htnciiEZNVjWL4DCPdCTjPOQvtqrSUNJj47HRYCDXbv2VYwt7nLr-yjxNBMdvdm6EOgVoIJIoN4uwEbXqFt-eC0a-I2Mc_sQLZxb34pCx1BosywTdcDlV5XYFVfz1w?key=Cio-1MWE9quPA-9Kw5rp-QgT)We can see that when the dataset is relatively small, our framework exceeds the performance of Spark. We can see that our framework is 1.6x faster than the Spark. This is because our framework is very lightweight and optimized for handling smaller datasets, allowing it to execute operations efficiently without the overhead that larger frameworks like Spark introduce. In contrast, Spark is designed to handle large-scale distributed data processing. While this makes it powerful for processing massive datasets, the associated overhead can result in slower performance for smaller datasets. 
\2. Spark vs RainStorm with 3000 lines, using simple operator: ![img](https://lh7-rt.googleusercontent.com/docsz/AD_4nXdWKUXJCtIIepcNePpeYH7EZObgV_PS4y78w0x4N2jdkjPauq6convFZm_2ZAI0oconU0aLO0kca5GkC90Trz-x6e7N1Td3fPshTn_Jdb0cwdvmv8DKifrcI5jLu7-h1B5otvAP?key=Cio-1MWE9quPA-9Kw5rp-QgT)When the dataset size increases significantly, Spark outperforms RainStorm, we can see that Spark is 1.7x faster than the RainStorm. This shows that Spark is fully optimized for handling large-scale distributed data processing. Its sophisticated scheduling algorithms, efficient data partitioning, and robust fault-tolerance mechanisms allow it to effectively manage the increased workload, maintaining scalability and consistent performance even as the dataset grows. In contrast, RainStorm, while highly efficient for smaller datasets, begins to experience performance degradation with larger datasets. This is due to the overhead introduced by its fault-tolerance mechanisms, such as exactly-once delivery and state recovery, which become more resource-intensive as the dataset size increases. Additionally, RainStorm’s lightweight design, which gives it an edge with smaller datasets, limits its ability to compete with Spark’s distributed processing capabilities at scale.
\3. Spark vs RainStorm with 1000 lines, using complex operator:![img](https://lh7-rt.googleusercontent.com/docsz/AD_4nXdFRUNPihvN7iklfYsO9d_mmlarXpK7C_NYuDo37oSqLxZI5B49LYFJu2lF8X8reS0jD0ZFcIy_ytgC0E-DVODwJJn3S6t3hMExQ08M-pm6Q8dorau70pF08iToIq5H9zxUzyxrvQ?key=Cio-1MWE9quPA-9Kw5rp-QgT)When using complex operators, we see that the time increases around 2.5-3x for both Spark and RainStorm compared to simple operators. This increase is because of additional computational workload and data shuffling required for executing multiple stages (Transform → FilteredTransform → AggregateByKey).
Spark vs RainStorm with 3000 lines, using complex operator![img](https://lh7-rt.googleusercontent.com/docsz/AD_4nXdjZysY0K_zHQ4dKRCUz7tMd4ndjgej6pnPYXipZnghfg4uV2VGPI9YSm6z6FlEv3vgjHTCLlpEY-TDoDx9uYW0pBLI0e2yNeD4d0omB1FP56ZhJSGnfWt5dxn3v9OiYCro7CGNSw?key=Cio-1MWE9quPA-9Kw5rp-QgT)For datasets with 3000 lines and a complex operator, Spark performance exceeds RainStorm more because of its fully optimized design for large-scale data processing.