package examples;

import org.datavec.api.io.filters.BalancedPathFilter;
import org.datavec.api.io.labels.ParentPathLabelGenerator;
import org.datavec.api.split.FileSplit;
import org.datavec.api.split.InputSplit;
import org.datavec.image.loader.NativeImageLoader;
import org.datavec.image.recordreader.ImageRecordReader;
import org.datavec.image.transform.FlipImageTransform;
import org.datavec.image.transform.ImageTransform;
import org.datavec.image.transform.PipelineImageTransform;
import org.datavec.image.transform.WarpImageTransform;
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.conf.GradientNormalization;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.distribution.GaussianDistribution;
import org.deeplearning4j.nn.conf.distribution.NormalDistribution;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.PerformanceListener;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.preprocessor.DataNormalization;
import org.nd4j.linalg.dataset.api.preprocessor.ImagePreProcessingScaler;
import org.nd4j.linalg.learning.config.Nesterovs;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.nd4j.linalg.primitives.Pair;
import org.nd4j.linalg.schedule.ScheduleType;
import org.nd4j.linalg.schedule.StepSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class AnimalClassifier {
    private static Logger log = LoggerFactory.getLogger("examples.AnimalClassifier.class");
    public static void main(String[] args) throws Exception {

        //R,G,B channels
        int channels = 3;
        int batchSize=10;

        //load files and split
        File parentDir = new File("/home/Deeplearning4J/imagenet");
        FileSplit fileSplit = new FileSplit(parentDir, NativeImageLoader.ALLOWED_FORMATS,new Random(42));
        int numLabels = fileSplit.getRootDir().listFiles(File::isDirectory).length;

        //identify labels in the path
        ParentPathLabelGenerator parentPathLabelGenerator = new ParentPathLabelGenerator();

        //file split to train/test using the weights.
        BalancedPathFilter balancedPathFilter = new BalancedPathFilter(new Random(42),NativeImageLoader.ALLOWED_FORMATS,parentPathLabelGenerator);
        InputSplit[] inputSplits = fileSplit.sample(balancedPathFilter,80,20);

        //get train/test data
        InputSplit trainData = inputSplits[0];
        InputSplit testData = inputSplits[1];

        //Data augmentation
        ImageTransform transform1 = new FlipImageTransform(new Random(42));
        ImageTransform transform2 = new FlipImageTransform(new Random(123));
        ImageTransform transform3 = new WarpImageTransform(new Random(42),42);

        //pipelines to specify image transformation.
        List<Pair<ImageTransform,Double>> pipeline = Arrays.asList(
                new Pair<>(transform1, 0.8),
                new Pair<>(transform2, 0.7),
                new Pair<>(transform3, 0.5)
        );

        ImageTransform transform = new PipelineImageTransform(pipeline);
        DataNormalization scaler = new ImagePreProcessingScaler(0,1);

        MultiLayerConfiguration config;
        config = new NeuralNetConfiguration.Builder()
                .weightInit(WeightInit.DISTRIBUTION)
                .dist(new NormalDistribution(0.0, 0.01))
                .activation(Activation.RELU)
                .updater(new Nesterovs(new StepSchedule(ScheduleType.ITERATION, 1e-2, 0.1, 100000), 0.9))
                .biasUpdater(new Nesterovs(new StepSchedule(ScheduleType.ITERATION, 2e-2, 0.1, 100000), 0.9))
                .gradientNormalization(GradientNormalization.RenormalizeL2PerLayer) // normalize to prevent vanishing or exploding gradients
                .l2(5 * 1e-4)
               // .weightInit(WeightInit.XAVIER)
               // .updater(new Nesterovs(0.008D,0.9D))
                .list()
                .layer(new ConvolutionLayer.Builder(11,11)
                        .nIn(channels)
                        .nOut(96)
                        .stride(4,4)
                        .activation(Activation.RELU)
                        .build())
                .layer(1, new LocalResponseNormalization.Builder().name("lrn1").build())
                .layer(new SubsamplingLayer.Builder(PoolingType.MAX)
                        .kernelSize(3,3)
                        .build())
                .layer(new ConvolutionLayer.Builder(5,5)
                        .nOut(256)
                        .stride(1,1)
                        .activation(Activation.RELU)
                        .build())
                .layer(1, new LocalResponseNormalization.Builder().name("lrn2").build())
                .layer(new SubsamplingLayer.Builder(PoolingType.MAX)
                        .kernelSize(3,3)
                        .build())
                .layer(new DenseLayer.Builder()
                        .nOut(500)
                        .dist(new GaussianDistribution(0, 0.005))
                        .activation(Activation.RELU)
                        .build())
                .layer(new DenseLayer.Builder()
                        .nOut(500)
                        .dist(new GaussianDistribution(0, 0.005))
                        .activation(Activation.RELU)
                        .build())
                .layer(new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                        .nOut(numLabels)
                        .activation(Activation.SOFTMAX)
                        .build())
                .setInputType(InputType.convolutional(30,30,3))
                .backprop(true).pretrain(false)
                .build();


        //train without transformations
        ImageRecordReader imageRecordReader = new ImageRecordReader(30,30,channels,parentPathLabelGenerator);
        imageRecordReader.initialize(trainData,null);
        DataSetIterator dataSetIterator = new RecordReaderDataSetIterator(imageRecordReader,batchSize,1,numLabels);
        scaler.fit(dataSetIterator);
        dataSetIterator.setPreProcessor(scaler);
        MultiLayerNetwork model = new MultiLayerNetwork(config);
        model.init();
        model.setListeners(new ScoreIterationListener(100)); //PerformanceListener for optimized training
        model.fit(dataSetIterator,100);

        //train with transformations
        imageRecordReader.initialize(trainData,transform);
        dataSetIterator = new RecordReaderDataSetIterator(imageRecordReader,batchSize,1,numLabels);
        scaler.fit(dataSetIterator);
        dataSetIterator.setPreProcessor(scaler);
        model.fit(dataSetIterator,10);

        imageRecordReader.initialize(testData);
        dataSetIterator = new RecordReaderDataSetIterator(imageRecordReader,batchSize,1,numLabels);
        scaler.fit(dataSetIterator);
        dataSetIterator.setPreProcessor(scaler);

        Evaluation evaluation = model.evaluate(dataSetIterator);
        System.out.println("args = [" + evaluation.stats() + "]");

        ModelSerializer.writeModel(model,new File("cnntrainedmodel.zip"),true);



    }
}
