package xz.lasercutter;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import javax.imageio.ImageIO;

import static xz.lasercutter.PropertyManager.*;

public class ImageConverter {
	private static final PrintMethod PRINT_METHODS[] = {
		new PrintMethodPrintByLine(),
		new PrintMethodPrintByLineFaster(),
		new PrintMethodBlockEdging(),
		new PrintMethodBlockEdgingWithErrorCorrection()
	};
	public static final int NUMBER_OF_PRINT_METHODS = PRINT_METHODS.length;
	
	private static String picturePath;
	private static int colorThreshold = 100; 
	
	private static int choicedPrintMethod = 3;
	
	
	public static int getChoicedPrintMethod() {
		return choicedPrintMethod;
	}

	public static void choicePrintMethod(int printMethod) {
		ImageConverter.choicedPrintMethod = printMethod;
	}

	public static String getPrintMethodName(int index) {
		try {
			return PRINT_METHODS[index].getName();
		} catch (ArrayIndexOutOfBoundsException e) {
			return null;
		}
	}
	
	private static int brightness(int color) {
		int sum = 0;
		sum += color & 0xff;
		color >>= 8;
		sum += color & 0xff;
		color >>= 8;
		sum += color & 0xff;
		return sum / 3;
	}
	
	public static void setPictuerPath(String st) {
		picturePath = st;
	}
	
	private static void generateCommandFile(int bitmap[][]) {
		try {
			PRINT_METHODS[choicedPrintMethod].generatePrintCommandList(bitmap, PropertyManager.getTempCmdPath());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public static void processPicture() {
		//open file
		if (picturePath == null)
			return;
		File picFile = new File(picturePath);
		BufferedImage bi;
		try {
			bi = ImageIO.read(picFile);
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
		//file read
		int height = bi.getHeight();
		int width = bi.getWidth();
		
		if (height > MOTOR_MOVE_DISTANCE || width > MOTOR_MOVE_DISTANCE) {
			MainWindow.log("SYSTEM\t|Image is too large.");
			return ;
		}
		
		int pixelArray[] = new int[MOTOR_MOVE_DISTANCE * MOTOR_MOVE_DISTANCE];
		for (int i = 0; i < pixelArray.length; ++i)
			pixelArray[i] = 0xFFFFFFFF;
		int offset = (MOTOR_MOVE_DISTANCE - height) / 2 * MOTOR_MOVE_DISTANCE + (MOTOR_MOVE_DISTANCE - width) / 2;
		bi.getRGB(0, 0, width, height, pixelArray, offset, MOTOR_MOVE_DISTANCE);
		
		int bitmap[][] = new int[MOTOR_MOVE_DISTANCE][MOTOR_MOVE_DISTANCE];
		for (int i = 0; i < MOTOR_MOVE_DISTANCE; ++i)
			for (int j = 0; j < MOTOR_MOVE_DISTANCE; ++j) {
				if (brightness(pixelArray[i * MOTOR_MOVE_DISTANCE + j]) > colorThreshold)
					bitmap[i][j] = 0;
				else
					bitmap[i][j] = 1;
			}
		// Convert picture to bitmap
		for (int i = 0; i < MOTOR_MOVE_DISTANCE; ++i)
			for (int j = 0; j < MOTOR_MOVE_DISTANCE; ++j) {
				if (brightness(pixelArray[i * MOTOR_MOVE_DISTANCE + j]) > colorThreshold)
					pixelArray[i * MOTOR_MOVE_DISTANCE + j] = 0xffffffff;
				else
					pixelArray[i * MOTOR_MOVE_DISTANCE + j] = 0xff000000;
			}
		
		// output modified picture
		BufferedImage nbi = new BufferedImage(MOTOR_MOVE_DISTANCE, MOTOR_MOVE_DISTANCE, BufferedImage.TYPE_INT_ARGB);
		nbi.setRGB(0, 0, MOTOR_MOVE_DISTANCE, MOTOR_MOVE_DISTANCE, pixelArray, 0, MOTOR_MOVE_DISTANCE);
		
		File testPic = new File(PropertyManager.getTempPicPath());
		try {
			ImageIO.write(nbi, "png", testPic);
		} catch (IOException e) {
			e.printStackTrace();
			return ;
		}

		// generate command file
		generateCommandFile(bitmap);

	}

	public static final int[] DX = {0, -1, -1, -1, 0, 1, 1, 1};
	public static final int[] DY = {-1, -1, 0, 1, 1, 1, 0, -1};
		
	private static class CommandGenerator {
		//TODO : for each command, record last direction, append correction command
		private int curX;
		private int curY;
		private int curL;
		
		private boolean enableErrorCorrection = false;
		private int lastDirX = 0;
		private int lastDirY = 0;
		
		public void setCorrection(boolean state) {
			enableErrorCorrection = state;
		}
		
		public int getX() {
			return curX;
		}
		
		public int getY() {
			return curY;
		}
		
		public int getLaser() {
			return curL;
		}
		
		public CommandGenerator(int x, int y, int l) {
			curX = x;
			curY = y;
			curL = l;
		}
		
		public CommandGenerator() {
			this(0, 0, 0);
		}
		
		private void recordLastDir(int dir) {
			if (DX[dir] != 0)
				lastDirX = DX[dir];
			if (DY[dir] != 0)
				lastDirY = DY[dir];
		}
		
		private String correction(int dirNext) {
			StringBuffer cmd = new StringBuffer();
			if (!enableErrorCorrection)
				return cmd.toString();
			if (DX[dirNext] * lastDirX == -1) {
				if (DX[dirNext] == 1)
					cmd.append("MOVE 6 " + PropertyManager.MOTOR_TURNING_EPS_X + ";\n");
				else
					cmd.append("MOVE 2 " + PropertyManager.MOTOR_TURNING_EPS_X + ";\n");
			}
			if (DY[dirNext] * lastDirY == -1) {
				if (DY[dirNext] == 1)
					cmd.append("MOVE 4 " + PropertyManager.MOTOR_TURNING_EPS_Y + ";\n");
				else
					cmd.append("MOVE 0 " + PropertyManager.MOTOR_TURNING_EPS_Y + ";\n");
			}
			
			return cmd.toString();
		}
		
		public String cDot(int dly, int brt) {
			String cmd = "DOT " + dly + " " + brt + ";\n";
			curL = 0;
			return cmd;
		}
		
		public String cMove(int dir, int len, int dly) {
			if (len < 0) {
				len = -len;
				dir = (dir + 4) % 8;
			}
			StringBuffer cmd = new StringBuffer();
			cmd.append(correction(dir));
			cmd.append("MOVE " + dir + " " + len + " " + dly + ";\n");
			recordLastDir(dir);
			curX += DX[dir] * len;
			curY += DY[dir] * len;
			return cmd.toString();
		}
		
		public String cLine(int dir, int len, int dly, int brt) {
			if (len < 0) {
				len = -len;
				dir = (dir + 4) % 8;
			}
			StringBuffer cmd = new StringBuffer();
			cmd.append(correction(dir));
			cmd.append("LINE " + dir + " " + len + " " + dly + " " + brt + ";\n");
			recordLastDir(dir);
			curX += DX[dir] * len;
			curY += DY[dir] * len;
			return cmd.toString();
		}
		
		public String cSteps(int dly, Queue<Integer> q) {
			StringBuffer cmd = new StringBuffer();
			int cnt = 0;
			cmd.append(correction(q.peek()));
			while (q.size() > 0) {
				if (cnt == 0)
					cmd.append("STEPS " + dly);
				cmd.append(" " + q.peek());
				recordLastDir(q.peek());
				q.remove();
				++cnt;
				String cor = null;
				if (q.size() > 0)
					cor = correction(q.peek());
				if ((cor != null && cor.length() > 0) || cnt == 9) {
					if (cnt < 9)
						cmd.append(" -1");
					cmd.append(";\n");
					cnt = 0;
					cmd.append(cor);
				}
			}
			if (cnt > 0) {
				if (cnt < 8)
					cmd.append(" -1");
				cmd.append(";\n");
			}
			return cmd.toString();
		}
		
		public String cStep(int dir) {
			StringBuffer cmd = new StringBuffer();
			cmd.append(correction(dir));
			cmd.append("STEP " + dir + ";\n");
			recordLastDir(dir);
			curX += DX[dir];
			curY += DY[dir];
			return cmd.toString();
		}
		
		public String cLaser(int brt) {
			curL = brt;
			return "LASER " + brt + ";\n";
		}

		public String cReset() {
			curX = 0;
			curY = 0;
			curL = 0;
			lastDirX = 0;
			lastDirY = 0;
			return "RESET;\n";
		}

		public String cReport() {
			return "REPORT;\n";
		}
		
		public String cWait(int dly) {
			return "WAIT " + dly + ";\n";
		}
		
	}
	
	// TODO: command generate function, comment in command list
	// Rewrite PrintMethod as a abstract class? Or write a new class?
	
	private interface PrintMethod {
		void generatePrintCommandList(int bitmap[][], String path) throws IOException;
		String getName();
	}
	private static class PrintMethodPrintByLineFaster implements PrintMethod {
		private static String name = "Print by Line (Faster)";
		
		public String getName() {
			return name;
		}
		
		public void generatePrintCommandList(int bitmap[][], String path) throws IOException {
			// more faster than 1
			File cmdList = new File(path);
			cmdList.createNewFile();
			FileWriter fw = new FileWriter(cmdList.getAbsolutePath());
			BufferedWriter bw = new BufferedWriter(fw);
			
			CommandGenerator cg = new CommandGenerator();
			bw.write(cg.cReset());
			
			int lineBegin = -1, preI = 0, preJ = 0;
			for (int i = 0; i < bitmap.length; ++i) {
				lineBegin = -1;
				for (int j = 0; j < bitmap[i].length; ++j) {
					if (bitmap[i][j] == 0)
						continue;
					// check if is on a start of a line, record the position, move laser here
					if (j == 0 || bitmap[i][j - 1] == 0) {
						lineBegin = j;
						int deltaI = i - preI, deltaJ = j - preJ;
						if (deltaI != 0)
							bw.write(cg.cMove(0, deltaI, 0));
						if (deltaJ > 0)
							bw.write(cg.cMove(2, deltaJ, 0));
						if (deltaJ < 0)
							bw.write(cg.cMove(6, -deltaJ, 0));
					}
					// check if is on a end of a line, draw the line
					if (j + 1 == bitmap[i].length || bitmap[i][j + 1] == 0) {
						bw.write(cg.cDot(PropertyManager.getDrawDotDelay(), PropertyManager.getDrawBrightness()));
						if (j - lineBegin > 0)
							bw.write(cg.cLine(2, j - lineBegin, PropertyManager.getDrawLineDelay(), PropertyManager.getDrawBrightness()));
						preI = i;
						preJ = j;
					}				
				}
			}
			bw.write(cg.cMove(4, preI, 0));
			bw.write(cg.cMove(6, preJ, 0));
			bw.write(cg.cReport());
			bw.close();
		}
	}
	private static class PrintMethodPrintByLine implements PrintMethod {
		private static String name = "Print by Line";
		
		public String getName() {
			return name;
		}
		
		public void generatePrintCommandList(int bitmap[][], String path) throws IOException {
			// convert bitmap to machine commands, brute force
			File cmdList = new File(path);
			cmdList.createNewFile();
			FileWriter fw = new FileWriter(cmdList.getAbsolutePath());
			BufferedWriter bw = new BufferedWriter(fw);
			
			CommandGenerator cg = new CommandGenerator();
			bw.write(cg.cReset());
			
			int len = 0;
			
			for (int i = 0; i < bitmap.length; ++i) {
				for (int j = 1; j < bitmap[i].length; ++j) {
					if (bitmap[i][j] == bitmap[i][j - 1])
						++len;
					else if (bitmap[i][j] == 1) {
						// 2 segments must separate with a space
						// write move command -
						bw.write(cg.cMove(2, len + 1, 0));
						// laser on (not needed)
						// dot command -
						len = 0;
					} else {
						// write draw command (move with delay, laser off) -
						bw.write(cg.cDot(PropertyManager.getDrawDotDelay(), PropertyManager.getDrawBrightness()));
						bw.write(cg.cLine(2, len, PropertyManager.getDrawLineDelay(), PropertyManager.getDrawBrightness()));
						bw.write(cg.cMove(2, 1, 0)); // extra step
						len = 0;
					}
				}
				// write uncompleted command
				if (bitmap[i][bitmap[i].length - 1] == 0) {
					bw.write(cg.cMove(2, len, 0));
				} else {
					bw.write(cg.cDot(PropertyManager.getDrawDotDelay(), PropertyManager.getDrawBrightness()));
					bw.write(cg.cLine(2, len, PropertyManager.getDrawLineDelay(), PropertyManager.getDrawBrightness()));
				}
				// return
				bw.write(cg.cMove(6, 1399, 0));
				// new line
				bw.write(cg.cMove(0, 1, 0));
			}
			bw.write(cg.cReport());
			bw.close();
		}
	}	
	private static class PrintMethodBlockEdgingWithErrorCorrection implements PrintMethod {
		private static String name = "Block Edging (Error Correction)";
		
		public String getName() {
			return name;
		}
		
		public void generatePrintCommandList(int bitmap[][], String path) throws IOException {
			// edging with error compensation
			File cmdList = new File(path);
			cmdList.createNewFile();
			FileWriter fw = new FileWriter(cmdList.getAbsolutePath());
			BufferedWriter bw = new BufferedWriter(fw);
			
			CommandGenerator cg = new CommandGenerator();
			cg.setCorrection(true);
			bw.write(cg.cReset());
			
			int bitmapBackup[][] = new int[bitmap.length][];
			for (int i = 0; i < bitmapBackup.length; ++i)
				bitmapBackup[i] = Arrays.copyOf(bitmap[i], bitmap[i].length);
			
			bitmap = bitmapBackup;
			
			int[] dy = {1, 1, 0, -1, -1, -1, 0, 1};
			int[] dx = {0, 1, 1, 1, 0, -1, -1, -1};
			int y = 0, x = 0;
			for (int i = 0; i < bitmap.length; ++i) {
				for (int j = 0, k; j < bitmap[i].length; ++j) {
					if (bitmap[i][j] == 1) {
						int dir = 2;
						// move here and laser on(xy -> ij), delay
						int deltaI = i - y, deltaJ = j - x;
						bw.write(cg.cMove(0, deltaI, 0));
						bw.write(cg.cMove(2, deltaJ, 0));
						bw.write(cg.cLaser(PropertyManager.getDrawBrightness()));
						bw.write(cg.cWait(PropertyManager.getDrawDotDelay()));
						// steps command should not delay on the start, because of the need of command consequence
						Queue<Integer> q = new LinkedList<Integer>();
						for (y = i, x = j; ; ) {
							int ty = y, tx = x;
							bitmap[y][x] = 0;
							for (k = 0, dir = (dir + 5) % 8; k < 8; ++k, dir = (dir + 1) % 8) {
								ty = y + dy[dir]; tx = x + dx[dir];
								if (ty >= 0 && ty < bitmap.length && tx > 0 && tx < bitmap[i].length && bitmap[ty][tx] != 0)
									break;
							}
							if (ty >= 0 && ty < bitmap.length && tx > 0 && tx < bitmap[i].length && bitmap[ty][tx] != 0) {
								y = ty; x = tx;
								q.add(dir);
							} else {
								// if queue is not empty, generate a command, end with -1
								if (q.size() > 0) {
									// write some steps command with error compensation
									bw.write(cg.cSteps(PropertyManager.getDrawLineDelay(), q));
																}
								// laser off
								bw.write(cg.cLaser(0));
								break;
							}
						}
					}
				}
			}
			bw.write(cg.cMove(4, y, 0));
			bw.write(cg.cMove(6, x, 0));
			bw.write(cg.cMove(0, 0, 0));
			bw.write(cg.cMove(2, 0, 0));
			bw.write(cg.cReport());
			bw.close();
		}
	}
	private static class PrintMethodBlockEdging implements PrintMethod {
		private static String name = "Block Edging";
		
		public String getName() {
			return name;
		}
		
		public void generatePrintCommandList(int bitmap[][], String path) throws IOException {
			// edging without error compensation
			File cmdList = new File(path);
			cmdList.createNewFile();
			FileWriter fw = new FileWriter(cmdList.getAbsolutePath());
			BufferedWriter bw = new BufferedWriter(fw);
			
			CommandGenerator cg = new CommandGenerator();
			bw.write(cg.cReset());
			
			int bitmapBackup[][] = new int[bitmap.length][];
			for (int i = 0; i < bitmapBackup.length; ++i)
				bitmapBackup[i] = Arrays.copyOf(bitmap[i], bitmap[i].length);
			
			bitmap = bitmapBackup;
			
			int[] dy = {1, 1, 0, -1, -1, -1, 0, 1};
			int[] dx = {0, 1, 1, 1, 0, -1, -1, -1};
			int y = 0, x = 0;
			for (int i = 0; i < bitmap.length; ++i) {
				for (int j = 0, k; j < bitmap[i].length; ++j) {
					if (bitmap[i][j] == 1) {
						int dir = 2;
						// move here and laser on(xy -> ij), delay
						int deltaI = i - y, deltaJ = j - x;
						bw.write(cg.cMove(0, deltaI, 0));
						bw.write(cg.cMove(2, deltaJ, 0));
						bw.write(cg.cLaser(PropertyManager.getDrawBrightness()));
						bw.write(cg.cWait(PropertyManager.getDrawDotDelay()));
						// steps command should not delay on the start, because of the need of command consequence
						Queue<Integer> q = new LinkedList<Integer>();
						for (y = i, x = j; ; ) {
							int ty = y, tx = x;
							bitmap[y][x] = 0;
							for (k = 0, dir = (dir + 5) % 8; k < 8; ++k, dir = (dir + 1) % 8) {
								ty = y + dy[dir]; tx = x + dx[dir];
								if (ty >= 0 && ty < bitmap.length && tx > 0 && tx < bitmap[i].length && bitmap[ty][tx] != 0)
									break;
							}
							if (ty >= 0 && ty < bitmap.length && tx > 0 && tx < bitmap[i].length && bitmap[ty][tx] != 0) {
								y = ty; x = tx;
								q.add(dir);
							} else {
								// if queue is not empty, generate a command, end with -1
								if (q.size() > 0) {
									// write some steps command with error compensation
									bw.write(cg.cSteps(PropertyManager.getDrawLineDelay(), q));
								}
								// laser off
								bw.write(cg.cLaser(0));
								break;
							}
						}
					}
				}
			}
			bw.write(cg.cMove(4, y, 0));
			bw.write(cg.cMove(6, x, 0));
			bw.write(cg.cReport());
			bw.close();
		}
	}
	
}

