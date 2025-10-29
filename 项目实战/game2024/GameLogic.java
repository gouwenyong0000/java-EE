package game2024;

/**
 * 游戏2024的核心逻辑类
 * 负责处理游戏的数据结构、移动逻辑和状态判断
 */
public class GameLogic {
    /** 游戏棋盘数组 */
    private int[][] board;
    /** 当前游戏分数 */
    private int score;
    /** 棋盘大小 */
    private final int size;
    /** 标记上一次移动是否产生了变化 */
    private boolean moved;

    /**
     * 构造函数
     * @param size 棋盘大小（通常为4）
     */
    public GameLogic(int size) {
        this.size = size;
        this.board = new int[size][size];
        this.score = 0;
        this.moved = false;
    }

    /**
     * 获取当前分数
     * @return 当前游戏分数
     */
    public int getScore() {
        return score;
    }

    /**
     * 获取当前棋盘状态
     * @return 棋盘数组
     */
    public int[][] getBoard() {
        return board;
    }

    /**
     * 向左移动所有数字
     * 相同的数字会合并，并增加相应的分数
     */
    public void moveLeft() {
        moved = false;
        for (int row = 0; row < size; row++) {
            // 合并相同的数字
            for (int col = 0; col < size - 1; col++) {
                for (int next = col + 1; next < size; next++) {
                    if (board[row][col] == 0) {
                        // 如果当前位置为空，将下一个非空数字移动到当前位置
                        if (board[row][next] != 0) {
                            board[row][col] = board[row][next];
                            board[row][next] = 0;
                            col--;
                            moved = true;
                            break;
                        }
                    } else if (board[row][next] != 0) {
                        // 如果找到相同的数字，进行合并
                        if (board[row][col] == board[row][next]) {
                            board[row][col] *= 2;
                            score += board[row][col];
                            board[row][next] = 0;
                            moved = true;
                        }
                        break;
                    }
                }
            }
        }
    }

    /**
     * 向右移动所有数字
     * 相同的数字会合并，并增加相应的分数
     */
    public void moveRight() {
        moved = false;
        for (int row = 0; row < size; row++) {
            for (int col = size - 1; col > 0; col--) {
                for (int prev = col - 1; prev >= 0; prev--) {
                    if (board[row][col] == 0) {
                        // 如果当前位置为空，将前一个非空数字移动到当前位置
                        if (board[row][prev] != 0) {
                            board[row][col] = board[row][prev];
                            board[row][prev] = 0;
                            col++;
                            moved = true;
                            break;
                        }
                    } else if (board[row][prev] != 0) {
                        // 如果找到相同的数字，进行合并
                        if (board[row][col] == board[row][prev]) {
                            board[row][col] *= 2;
                            score += board[row][col];
                            board[row][prev] = 0;
                            moved = true;
                        }
                        break;
                    }
                }
            }
        }
    }

    /**
     * 向上移动所有数字
     * 相同的数字会合并，并增加相应的分数
     */
    public void moveUp() {
        moved = false;
        for (int col = 0; col < size; col++) {
            for (int row = 0; row < size - 1; row++) {
                for (int next = row + 1; next < size; next++) {
                    if (board[row][col] == 0) {
                        // 如果当前位置为空，将下一个非空数字移动到当前位置
                        if (board[next][col] != 0) {
                            board[row][col] = board[next][col];
                            board[next][col] = 0;
                            row--;
                            moved = true;
                            break;
                        }
                    } else if (board[next][col] != 0) {
                        // 如果找到相同的数字，进行合并
                        if (board[row][col] == board[next][col]) {
                            board[row][col] *= 2;
                            score += board[row][col];
                            board[next][col] = 0;
                            moved = true;
                        }
                        break;
                    }
                }
            }
        }
    }

    /**
     * 向下移动所有数字
     * 相同的数字会合并，并增加相应的分数
     */
    public void moveDown() {
        moved = false;
        for (int col = 0; col < size; col++) {
            for (int row = size - 1; row > 0; row--) {
                for (int prev = row - 1; prev >= 0; prev--) {
                    if (board[row][col] == 0) {
                        // 如果当前位置为空，将前一个非空数字移动到当前位置
                        if (board[prev][col] != 0) {
                            board[row][col] = board[prev][col];
                            board[prev][col] = 0;
                            row++;
                            moved = true;
                            break;
                        }
                    } else if (board[prev][col] != 0) {
                        // 如果找到相同的数字，进行合并
                        if (board[row][col] == board[prev][col]) {
                            board[row][col] *= 2;
                            score += board[row][col];
                            board[prev][col] = 0;
                            moved = true;
                        }
                        break;
                    }
                }
            }
        }
    }

    /**
     * 检查是否还能移动
     * @return 如果还有可能的移动返回true，否则返回false
     */
    public boolean canMove() {
        // 检查是否还有空格
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (board[i][j] == 0) {
                    return true;
                }
            }
        }

        // 检查是否有相邻的相同数字
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (j < size - 1 && board[i][j] == board[i][j + 1]) return true;
                if (i < size - 1 && board[i][j] == board[i + 1][j]) return true;
            }
        }

        return false;
    }

    /**
     * 检查上一次移动是否产生了变化
     * @return 如果产生了变化返回true，否则返回false
     */
    public boolean hasMoved() {
        return moved;
    }

    /**
     * 设置指定位置的数字
     * @param row 行坐标
     * @param col 列坐标
     * @param value 要设置的值
     */
    public void setTile(int row, int col, int value) {
        board[row][col] = value;
    }

    /**
     * 检查是否达到胜利条件（出现2024）
     * @return 如果出现2024返回true，否则返回false
     */
    public boolean hasWon() {
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (board[i][j] == 2024) return true;
            }
        }
        return false;
    }
}
