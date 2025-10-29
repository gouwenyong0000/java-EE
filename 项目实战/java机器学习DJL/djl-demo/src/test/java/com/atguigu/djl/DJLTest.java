package com.atguigu.djl;

import ai.djl.MalformedModelException;
import ai.djl.Model;
import ai.djl.basicdataset.cv.classification.ImageFolder;
import ai.djl.basicdataset.cv.classification.Mnist;
import ai.djl.basicmodelzoo.basic.Mlp;
import ai.djl.inference.Predictor;
import ai.djl.metric.Metrics;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.transform.CenterCrop;
import ai.djl.modality.cv.transform.Resize;
import ai.djl.modality.cv.transform.ToTensor;
import ai.djl.modality.cv.translator.ImageClassificationTranslator;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.EasyTrain;
import ai.djl.training.Trainer;
import ai.djl.training.TrainingResult;
import ai.djl.training.dataset.Dataset;
import ai.djl.training.dataset.RandomAccessDataset;
import ai.djl.training.evaluator.Accuracy;
import ai.djl.training.listener.TrainingListener;
import ai.djl.training.loss.Loss;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DJLTest {
   //完全训练一个模型：
    // 1、准备数据集
    // 2、构建神经网络
    // 3、构建模型（这个模型应用上面的神经网络）
    // 4、训练配置（如何训练、训练集、验证集、测试集）
    // 5、保存模型
    // ======下面流程===
    // 6、加载模型
    // 7、预测（给模型一个新输入，让他判断这是什么）
    @SneakyThrows
    @Test
    void trainingComplete() throws Exception {
        // 1、准备数据集; 用自己的数据就自定义 Dataset
        RandomAccessDataset trainingSet = getDataset(Dataset.Usage.TRAIN);
        RandomAccessDataset validateSet = getDataset(Dataset.Usage.TEST);

        //2、构建神经网络； 就是 block
        //输入：28*28  输出：10
        Mlp mlp = new Mlp(Mnist.IMAGE_WIDTH * Mnist.IMAGE_WIDTH, Mnist.NUM_CLASSES, new int[]{128, 64});

        //3、创建模型，加载这个block
        try (Model model = Model.newInstance("mlp")){
            //设置神经网络
            model.setBlock(mlp);

            //4、训练配置
            String outputDir = "build/mlp";
            DefaultTrainingConfig config = new DefaultTrainingConfig(Loss.softmaxCrossEntropyLoss())
                    .addEvaluator(new Accuracy())
                    .addTrainingListeners(TrainingListener.Defaults.logging(outputDir));

            //5、获取训练器
            try (Trainer trainer = model.newTrainer(config)) {
                trainer.setMetrics(new Metrics());
                //6、初始化
                trainer.initialize(new Shape(1, Mnist.IMAGE_HEIGHT * Mnist.IMAGE_WIDTH));

                //7、训练5次
                EasyTrain.fit(trainer, 5, trainingSet, validateSet);

                TrainingResult result = trainer.getTrainingResult();
                System.out.println("训练结果..." + result);

                //8、保存模型  DJL
                model.save(Paths.get(outputDir),"mlp");

                System.out.println("模型保存成功");

            }



        };


    }

    @SneakyThrows
    private RandomAccessDataset getDataset(Dataset.Usage usage) throws Exception{
        //1、Mnist 是内置的，数据已经带好了 用户目录/.djl/cache

        //这个就是数据集、自定义的数据集，需要写translator
//        ImageFolder build = ImageFolder.builder()
//                .setRepositoryPath("~/.djl/cache")
//                .optImageHeight(28)
//                .optImageWidth(28)
//                .setSampling(64,true)
//                .build();
//

        Mnist mnist = Mnist.builder()
                .setSampling(64, true)
                .optUsage(usage)
                .build();
        mnist.prepare(new ProgressBar());
        return mnist;
    }

    //测试模型
    @Test
    void predictTest() throws IOException, MalformedModelException, TranslateException {
        //1、准备测试数据
        Image image = ImageFactory.getInstance().fromUrl("https://resources.djl.ai/images/0.png");
        Image img2 = ImageFactory.getInstance().fromFile(Paths.get("build/img/7.png"));

        //2、加载模型
        Path modelDir = Paths.get("build/mlp");
        Model model = Model.newInstance("mlp");
        model.setBlock(new Mlp(28 * 28, 10, new int[] {128, 64}));
        model.load(modelDir);


        //3、获取一个转化器； 黑底白字， 白底黑字
        ImageClassificationTranslator translator = ImageClassificationTranslator.builder()
                .addTransform(new Resize(28, 28))
                 //加处理逻辑
                .addTransform(new ToTensor())
                .build();

        //4、获取预测器
        Predictor<Image, Classifications> predictor = model.newPredictor(translator);

        //5、预测图片分类
        Classifications predict = predictor.predict(img2);

        System.out.println(predict);

    }

 


    //N维向量
    //数据集加载过来 ==> N维向量（Translator【转换器】）
    @Test
    void test01() {
        try (NDManager manager = NDManager.newBaseManager()) {

            // 2x3 的矩阵
            NDArray ones = manager.ones(new Shape(2, 3));
            System.out.println("ones = " + ones);


            NDArray array = manager.create(new float[]{1.2f, 2.3f, 1.1f, 0.9f}, new Shape(2, 2));
            System.out.println("array = " + array);

            NDArray transpose = array.transpose();
            System.out.println("transpose = " + transpose);


        }
    }

}
