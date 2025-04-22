package game2024;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * 2024游戏的主类
 * 负责创建游戏窗口、处理用户输入和显示游戏界面
 */
public class Game2024 extends JFrame {
    /** 游戏棋盘的大小（N x N的网格） */
    private static final int GRID_SIZE = 4;
    /** 每个格子的大小（像素） */
    private static final int TILE_SIZE = 100;
    /** 格子之间的间隔（像素） */
    private static final int GRID_GAP = 10;

    /** 游戏面板 */
    private GameBoard gameBoard;
    /** 游戏逻辑处理器 */
    private GameLogic gameLogic;
    /** 分数显示标签 */
    private JLabel scoreLabel;

    /**
     * 构造函数
     * 初始化游戏窗口和组件
     */
    public Game2024() {
        gameLogic = new GameLogic(GRID_SIZE);
        setTitle("2024 游戏");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        // 初始化游戏面板
        gameBoard = new GameBoard();
        scoreLabel = new JLabel("分数: 0");
        scoreLabel.setFont(new Font("Arial", Font.BOLD, 20));

        // 设置布局
        setLayout(new BorderLayout());
        add(scoreLabel, BorderLayout.NORTH);
        add(gameBoard, BorderLayout.CENTER);

        // 添加键盘监听器
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_LEFT:
                        moveLeft();
                        break;
                    case KeyEvent.VK_RIGHT:
                        moveRight();
                        break;
                    case KeyEvent.VK_UP:
                        moveUp();
                        break;
                    case KeyEvent.VK_DOWN:
                        moveDown();
                        break;
                }
                updateScore();
                gameBoard.repaint();
            }
        });

        pack();
        setLocationRelativeTo(null);
        setFocusable(true);
    }

    /**
     * 向左移动所有数字
     */
    private void moveLeft() {
        gameLogic.moveLeft();
        afterMove();
    }

    /**
     * 向右移动所有数字
     */
    private void moveRight() {
        gameLogic.moveRight();
        afterMove();
    }

    /**
     * 向上移动所有数字
     */
    private void moveUp() {
        gameLogic.moveUp();
        afterMove();
    }

    /**
     * 向下移动所有数字
     */
    private void moveDown() {
        gameLogic.moveDown();
        afterMove();
    }

    /**
     * 移动后的处理逻辑
     * 检查游戏状态，添加新数字，更新界面
     */
    private void afterMove() {
        if (gameLogic.hasMoved()) {
            gameBoard.addNewNumber();
            if (gameLogic.hasWon()) {
                JOptionPane.showMessageDialog(this, "恭喜你赢了！", "游戏胜利", JOptionPane.INFORMATION_MESSAGE);
                resetGame();
            } else if (!gameLogic.canMove()) {
                JOptionPane.showMessageDialog(this, "游戏结束！\n最终分数: " + gameLogic.getScore(),
                    "游戏结束", JOptionPane.INFORMATION_MESSAGE);
                resetGame();
            }
        }
    }

    /**
     * 重置游戏
     * 创建新的游戏逻辑实例并初始化棋盘
     */
    private void resetGame() {
        gameLogic = new GameLogic(GRID_SIZE);
        gameBoard.initializeBoard();
        updateScore();
    }

    /**
     * 更新分数显示
     */
    private void updateScore() {
        scoreLabel.setText("分数: " + gameLogic.getScore());
    }

    /**
     * 程序入口点
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Game2024 game = new Game2024();
            game.setVisible(true);
        });
    }

    /**
     * 游戏面板类
     * 负责绘制游戏界面和处理游戏显示逻辑
     */
    private class GameBoard extends JPanel {
        /**
         * 构造函数
         * 设置面板的首选大小和背景色
         */
        public GameBoard() {
            setPreferredSize(new Dimension(
                GRID_SIZE * TILE_SIZE + (GRID_SIZE + 1) * GRID_GAP,
                GRID_SIZE * TILE_SIZE + (GRID_SIZE + 1) * GRID_GAP
            ));
            setBackground(new Color(187, 173, 160));
            initializeBoard();
        }

        /**
         * 初始化游戏棋盘
         * 添加两个初始数字
         */
        public void initializeBoard() {
            addNewNumber();
            addNewNumber();
        }

        /**
         * 在随机空位置添加新数字
         * 90%概率生成2，10%概率生成4
         */
        public void addNewNumber() {
            java.util.List<Point> emptyCells = new java.util.ArrayList<>();
            int[][] board = gameLogic.getBoard();

            // 找出所有空位置
            for (int i = 0; i < GRID_SIZE; i++) {
                for (int j = 0; j < GRID_SIZE; j++) {
                    if (board[i][j] == 0) {
                        emptyCells.add(new Point(i, j));
                    }
                }
            }

            // 在随机空位置添加新数字
            if (!emptyCells.isEmpty()) {
                Point cell = emptyCells.get((int) (Math.random() * emptyCells.size()));
                gameLogic.setTile(cell.x, cell.y, Math.random() < 0.9 ? 2 : 4);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            // 启用抗锯齿
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int[][] board = gameLogic.getBoard();
            // 绘制所有格子
            for (int row = 0; row < GRID_SIZE; row++) {
                for (int col = 0; col < GRID_SIZE; col++) {
                    drawTile(g2d, row, col, board[row][col]);
                }
            }
        }

        /**
         * 绘制单个格子
         * @param g2d 图形上下文
         * @param row 行坐标
         * @param col 列坐标
         * @param value 格子中的数值
         */
        private void drawTile(Graphics2D g2d, int row, int col, int value) {
            int x = GRID_GAP + col * (TILE_SIZE + GRID_GAP);
            int y = GRID_GAP + row * (TILE_SIZE + GRID_GAP);

            // 绘制格子背景
            g2d.setColor(getTileColor(value));
            g2d.fillRoundRect(x, y, TILE_SIZE, TILE_SIZE, 10, 10);

            // 绘制数字
            if (value != 0) {
                g2d.setColor(value <= 4 ? new Color(119, 110, 101) : Color.WHITE);
                g2d.setFont(new Font("Arial", Font.BOLD, 36));
                String text = String.valueOf(value);
                FontMetrics fm = g2d.getFontMetrics();
                int textX = x + (TILE_SIZE - fm.stringWidth(text)) / 2;
                int textY = y + (TILE_SIZE - fm.getHeight()) / 2 + fm.getAscent();
                g2d.drawString(text, textX, textY);
            }
        }

        /**
         * 根据数值获取对应的颜色
         * @param value 格子中的数值
         * @return 对应的颜色
         */
        private Color getTileColor(int value) {
            switch (value) {
                case 0: return new Color(205, 193, 180);    // 空格子颜色
                case 2: return new Color(238, 228, 218);    // 2的颜色
                case 4: return new Color(237, 224, 200);    // 4的颜色
                case 8: return new Color(242, 177, 121);    // 8的颜色
                case 16: return new Color(245, 149, 99);    // 16的颜色
                case 32: return new Color(246, 124, 95);    // 32的颜色
                case 64: return new Color(246, 94, 59);     // 64的颜色
                case 128: return new Color(237, 207, 114);  // 128的颜色
                case 256: return new Color(237, 204, 97);   // 256的颜色
                case 512: return new Color(237, 200, 80);   // 512的颜色
                case 1024: return new Color(237, 197, 63);  // 1024的颜色
                case 2024: return new Color(237, 194, 46);  // 2024的颜色
                default: return new Color(238, 228, 218);   // 其他数值的默认颜色
            }
        }
    }
}
