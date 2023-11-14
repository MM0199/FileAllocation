import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;


class Disk {
    static byte[][] blocks;

    static final int NUM_BLOCKS = 256;
    static final int BLOCKS_SIZE = 512;

    Disk() {
        blocks = new byte[256][512];
        // set bitmap
        blocks[1][0] = 1;
        blocks[1][1] = 1;

        // initial file allocation table
        byte fillValue = -1;
        for(int i = 0; i < BLOCKS_SIZE; i++){
            blocks[0][i] = fillValue;
        }
    }

    public static void write(int blockNumber, byte[] data) {
        if (blockNumber >= 0 && blockNumber < NUM_BLOCKS) {
            if (data.length <= BLOCKS_SIZE) {
                System.arraycopy(data, 0, blocks[blockNumber], 0, data.length);
                // update bitmap
                blocks[1][blockNumber] = 1;
            } else {
                System.err.println("Not Enough Space.");
            }
        } else {
            System.err.println("Invalid block number.");
        }
    }

    public static byte[] read(int blockNumber) {
        if (blockNumber >= 0 && blockNumber < NUM_BLOCKS) {
            return Arrays.copyOf(blocks[blockNumber], BLOCKS_SIZE);
        } else {
            System.err.println("Invalid block number.");
            return new byte[BLOCKS_SIZE];
        }
    }
}

class FileSystem {

    static abstract class AllocationMethod{
        public abstract boolean createFile(String name);

        // find file and return its content
        public abstract void getData(String name);

        // delete a file
        public abstract void deleteFile(String name);

        // copy file
        public abstract void copyFile(String sourceName, String destName);

        // display bitmap
        public void displayBitmap(){
            for(int i = 0; i < Disk.NUM_BLOCKS; i++){
                if(i % 32 == 0){
                    System.out.println();
                }
                System.out.print(Disk.blocks[1][i]);
            }
            System.out.println();
        }

        // display block
        public void displayBlock(int blockNum){
            for(int i = 0; i < Disk.BLOCKS_SIZE; i++){
                if(i % 32 == 0){
                    System.out.println();
                }
                System.out.print(Disk.blocks[blockNum][i]);
            }
        }

        // display file allocation table
        public abstract void displayFAT(byte[] data);
    }

    static class Contiguous extends AllocationMethod {

        @Override
        // create file
        public boolean createFile(String fileName){
            try {
                // set up the file
                byte[] fileDataBytes = toBytes(fileName); // data
                byte[] fileSizeBytes = String.valueOf(fileDataBytes.length).getBytes(StandardCharsets.UTF_8);
                String[] name = fileName.split("\\.");
                byte[] fileNameBytes = name[0].getBytes(StandardCharsets.UTF_8); // name
                // calculate the number of blocks
                int blockNeed = (int) Math.ceil(fileDataBytes.length / 512.0);
                byte[] fileBlockSize = String.valueOf(blockNeed).getBytes(StandardCharsets.UTF_8); // block size
                int startBlock = findBlockFree(blockNeed);
                if (startBlock == -1) {
                    System.out.println("Not enough space.");
                    return false;
                }
                byte[] fileStartBlock = String.valueOf(startBlock).getBytes(StandardCharsets.UTF_8); // starting block
                // Combine all the file information
                byte fillValue = -1;
                byte[] fileInfor = new byte[17];
                Arrays.fill(fileInfor, fillValue); // set the fileInfor to null
                System.arraycopy(fileNameBytes, 0, fileInfor, 0, fileNameBytes.length);
                System.arraycopy(fileStartBlock, 0, fileInfor, 8, fileStartBlock.length);
                System.arraycopy(fileBlockSize, 0, fileInfor, 11, fileBlockSize.length);
                System.arraycopy(fileSizeBytes, 0, fileInfor, 13, fileSizeBytes.length);

                // add file information to FAT
                System.arraycopy(fileInfor, 0, Disk.blocks[0], findFATFree(17), fileInfor.length);

                // write data file to disk
                for (int i = 0; i < blockNeed; i++) {
                    int blockPtr = startBlock + i;
                    byte[] partitionFile = Arrays.copyOfRange(fileDataBytes, (Disk.BLOCKS_SIZE * i), (Disk.BLOCKS_SIZE * (i + 1)));
                    Disk.write(blockPtr, partitionFile);
                }
                return true;
            } catch (IOException e){
                System.err.println("No such file in current directory.");
            }
            return false;
        }

        @Override
        // find file and return its content
        public void getData(String name) {
            boolean isFound = false;
            // convert name to bytes
            byte[] nameBytes = new byte[8];
            byte fillValue = -1;
            Arrays.fill(nameBytes, fillValue);
            byte[] dummy = name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(dummy, 0, nameBytes, 0, dummy.length);
            int startIndex = 0;
            int blockSize = 0;
            int dataSize = 0;
            // Search the FAT and return the file information.
            for (int i = 0; i < 30; i++) {
                byte[] parts = Arrays.copyOfRange(Disk.read(0), 17 * i, (17 * (i + 1)));
                byte[] dummyParts = Arrays.copyOfRange(parts, 0, 8);
                if (Arrays.equals(nameBytes, dummyParts)) {
                    dummyParts = Arrays.copyOfRange(parts, 8, 11);
                    startIndex = bytesToInt(dummyParts);
                    dummyParts = Arrays.copyOfRange(parts, 11, 13);
                    blockSize = bytesToInt(dummyParts);
                    dummyParts = Arrays.copyOfRange(parts, 13, 17);
                    dataSize = bytesToInt(dummyParts);
                    isFound = true;
                    break;
                }
            }
            if(!isFound){
                System.out.println("File not found!");
                return;
            }
            byte[] dataBytes = new byte[dataSize];
            int index = 0;
            for (int i = startIndex; i < (startIndex + blockSize); i++) {
                if(dataSize > Disk.BLOCKS_SIZE){
                    System.arraycopy(Disk.read(i), 0,dataBytes, index, Disk.BLOCKS_SIZE);
                    dataSize -= Disk.BLOCKS_SIZE;
                    index += Disk.BLOCKS_SIZE;
                } else {
                    System.arraycopy(Disk.read(i), 0, dataBytes, index, dataSize);
                }
            }
            System.out.println(Arrays.toString(dataBytes));
        }

        @Override
        // delete a file
        public void deleteFile(String name){
            boolean isFound = false;
            // convert name to bytes
            byte[] nameBytes = new byte[8];
            byte fillValue = -1;
            Arrays.fill(nameBytes, fillValue);
            byte[] dummy = name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(dummy, 0, nameBytes, 0, dummy.length);
            int startIndex = 0;
            int blockSize = 0;
            // Search the FAT and return the file information.
            for (int i = 0; i < 30; i++) {
                byte[] parts = Arrays.copyOfRange(Disk.read(0), 17 * i, (17 * (i + 1)));
                byte[] dummyParts = Arrays.copyOfRange(parts, 0, 8);
                if (Arrays.equals(nameBytes, dummyParts)) {
                    dummyParts = Arrays.copyOfRange(parts, 8, 11);
                    startIndex = bytesToInt(dummyParts);
                    dummyParts = Arrays.copyOfRange(parts, 11, 13);
                    blockSize = bytesToInt(dummyParts);
                    // update FAT
                    for(int j = 17 * i; j < (17 * (i + 1)); j++){
                        Disk.blocks[0][j] = fillValue;
                    }
                    isFound = true;
                    break;
                }
            }
            if(!isFound){
                System.out.println("File not found!");
                return;
            }
            // delete the file data
            for (int i = 0; i < blockSize; i++){
                for(int j = 0; j < Disk.BLOCKS_SIZE; j++){
                    Disk.blocks[startIndex + i][j] = (byte) 0;
                }
            }
            // update bitmap
            for (int i = startIndex; i < (startIndex + blockSize); i++){
                Disk.blocks[1][i] = (byte) 0;
            }
        }

        @Override
        // copy file
        public void copyFile(String sourceName, String destName){
            boolean isFound = false;
            // convert name to bytes
            byte[] nameBytes = new byte[8];
            byte fillValue = -1;
            Arrays.fill(nameBytes, fillValue);
            byte[] dummy = sourceName.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(dummy, 0, nameBytes, 0, dummy.length);
            int startIndex = 0;
            int blockSize = 0;
            int dataSize = 0;
            // Search the FAT and return the file information.
            for (int i = 0; i < 30; i++) {
                byte[] parts = Arrays.copyOfRange(Disk.read(0), 17 * i, (17 * (i + 1)));
                byte[] dummyParts = Arrays.copyOfRange(parts, 0, 8);
                if (Arrays.equals(nameBytes, dummyParts)) {
                    dummyParts = Arrays.copyOfRange(parts, 8, 11);
                    startIndex = bytesToInt(dummyParts);
                    dummyParts = Arrays.copyOfRange(parts, 11, 13);
                    blockSize = bytesToInt(dummyParts);
                    dummyParts = Arrays.copyOfRange(parts, 13, 17);
                    dataSize = bytesToInt(dummyParts);
                    isFound = true;
                    break;
                }
            }
            if(!isFound){
                System.out.println("File not found!");
                return;
            }
            // set up the copy file
            byte[] copyByte = new byte[dataSize];
            int index = 0;

            // get the data from disk
            for(int i = startIndex; i < (startIndex + blockSize); i++){
                if(dataSize > Disk.BLOCKS_SIZE){
                    System.arraycopy(Disk.read(i), 0,copyByte, index, Disk.BLOCKS_SIZE);
                    dataSize -= Disk.BLOCKS_SIZE;
                    index += Disk.BLOCKS_SIZE;
                } else {
                    System.arraycopy(Disk.read(i), 0, copyByte, index, dataSize);
                }
            }

            Path currentDirectory = Paths.get(System.getProperty("user.dir"));
            Path filePath = currentDirectory.resolve(destName);
            if(!Files.isRegularFile(filePath)){
                System.out.println("Error: The destination file must be in the current directory.");
                return;
            }

            try(FileOutputStream outputStream = new FileOutputStream(destName)) {
                outputStream.write(copyByte);
                System.out.println("File copy successfully at: " + filePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // convert file to bytes
        static byte[] toBytes(String fileName) throws IOException {
            Path path = Paths.get(fileName);
            return Files.readAllBytes(path);
        }

        @Override
        // display file allocation table
        public void displayFAT(byte[] data){
            for(int i = 0; i < 30; i++) {
                byte[] parts = Arrays.copyOfRange(data, 17 * i, (17 * (i + 1)));
                if (parts[0] != -1) {
                    System.out.println(extractName(parts) + " " + extractStartBlock(parts) + " " + extractSize(parts));
                }
            }
        }

        // extract file size from next 2 bytes
        static String extractSize(byte[] data){
            byte fillSpace = 32;
            byte[] fileSize;
            fileSize = Arrays.copyOfRange(data, 11, 13);
            for(int i = 0; i < fileSize.length; i++){
                if(fileSize[i] == -1){
                    fileSize[i] = fillSpace;
                }
            }
            return new String(fileSize, StandardCharsets.UTF_8);
        }
    }

    static class Chained extends AllocationMethod{

        static int findBlockFree(int start){
            for(int i = start; i < Disk.NUM_BLOCKS; i++){
                if(Disk.blocks[1][i] == 0){
                    return i;
                }
            }
            return -1;
        }

        @Override
        public boolean createFile(String fileName){
            try {
                // set up the file
                byte[] fileDataBytes = toBytes(fileName); // data
                byte[] fileSizeBytes = String.valueOf(fileDataBytes.length).getBytes(StandardCharsets.UTF_8);
                String[] name = fileName.split("\\.");
                byte[] fileNameBytes = name[0].getBytes(StandardCharsets.UTF_8); // name
                // calculate the number of blocks
                int blockNeed = (int) Math.ceil(fileDataBytes.length / 509.0);
                if(blockNeed <= numBlockFree()){
                    int endBlock = -1;
                    int countBlock = 0;
                    int index = 0;
                    int[] blockArray = new int[blockNeed+1];
                    blockArray[blockArray.length - 1] = -1; // null pointer for last block.
                    // find start and end blocks
                    while(countBlock != blockNeed){
                        int blocks = findBlockFree(index);
                        if(blocks != -1){
                            blockArray[countBlock] = blocks;
                            countBlock++;
                            index = blocks+1;
                        }
                        if(countBlock == blockNeed){
                            endBlock = blockArray[countBlock-1];
                        }
                    }

                    byte[] fileStartBlock = String.valueOf(blockArray[0]).getBytes(StandardCharsets.UTF_8); // starting block
                    byte[] fileEndBlock = String.valueOf(endBlock).getBytes(StandardCharsets.UTF_8); // ending block
                    // Combine all the file information
                    byte fillValue = -1;
                    byte[] fileInfor = new byte[18];
                    Arrays.fill(fileInfor, fillValue); // set the fileInfor to null
                    System.arraycopy(fileNameBytes, 0, fileInfor, 0, fileNameBytes.length);
                    System.arraycopy(fileStartBlock, 0, fileInfor, 8, fileStartBlock.length);
                    System.arraycopy(fileEndBlock, 0, fileInfor, 11, fileEndBlock.length);
                    System.arraycopy(fileSizeBytes, 0, fileInfor, 14, fileSizeBytes.length);
                    // add file information to FAT
                    System.arraycopy(fileInfor, 0, Disk.blocks[0], findFATFree(18), fileInfor.length);
                    // write data file to disk
                    for(int i = 0; i < blockNeed; i++){
                        int currentBlock = blockArray[i];
                        int nextBlock = blockArray[i+1];
                        byte[] nextBlockBytes = String.valueOf(nextBlock).getBytes(StandardCharsets.UTF_8);
                        byte[] dummy = Arrays.copyOfRange(fileDataBytes, ((Disk.BLOCKS_SIZE - 3) * i), ((Disk.BLOCKS_SIZE - 3) * (i + 1)));
                        byte[] partitionFile = new byte[dummy.length + 3];
                        Arrays.fill(partitionFile, (byte) 0);
                        partitionFile = Arrays.copyOfRange(dummy, 0,partitionFile.length);
                        System.arraycopy(nextBlockBytes, 0, partitionFile, partitionFile.length - nextBlockBytes.length, nextBlockBytes.length);
                        Disk.write(currentBlock, partitionFile);
                    }
                    return true;
                } else {
                    System.out.println("Not enough space.");
                    return false;
                }
            } catch (IOException e){
                e.printStackTrace();
            }
            return false;
        }

        @Override
        public void getData(String name) {
            boolean isFound = false;
            // convert name to bytes
            byte[] nameBytes = new byte[8];
            byte fillValue = -1;
            Arrays.fill(nameBytes, fillValue);
            byte[] dummy = name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(dummy, 0, nameBytes, 0, dummy.length);
            int startIndex = 0;
            int dataSize = 0;
            // Search the FAT and return the file information.
            for (int i = 0; i < 28; i++) {
                byte[] parts = Arrays.copyOfRange(Disk.read(0), 18 * i, (18 * (i + 1)));
                byte[] dummyParts = Arrays.copyOfRange(parts, 0, 8);
                if (Arrays.equals(nameBytes, dummyParts)) {
                    dummyParts = Arrays.copyOfRange(parts, 8, 11);
                    startIndex = bytesToInt(dummyParts);
                    dummyParts = Arrays.copyOfRange(parts,14,18);
                    dataSize = bytesToInt(dummyParts);
                    isFound = true;
                    break;
                }
            }
            if(!isFound){
                System.out.println("File not found!");
                return;
            }
            byte[] dataBytes = new byte[dataSize];
            int index = startIndex;
            int ptr = 0;
            byte[] nextBlock;
            while(index != -1){
                if(dataSize > Disk.BLOCKS_SIZE - 3){
                    System.arraycopy(Disk.read(index), 0, dataBytes, ptr, Disk.BLOCKS_SIZE - 3);
                    dataSize -= Disk.BLOCKS_SIZE - 3;
                    ptr += Disk.BLOCKS_SIZE - 3;
                } else {
                    System.arraycopy(Disk.read(index), 0, dataBytes, ptr, dataSize);
                }

                nextBlock = Arrays.copyOfRange(Disk.read(index), Disk.BLOCKS_SIZE - 3, Disk.BLOCKS_SIZE);
                index = bytesToInt(nextBlock);
            }
            System.out.println(Arrays.toString(dataBytes));
        }

        @Override
        public void deleteFile(String name) {
            boolean isFound = false;
            // convert name to bytes
            byte[] nameBytes = new byte[8];
            Arrays.fill(nameBytes, (byte) -1);
            byte[] dummy = name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(dummy, 0, nameBytes, 0, dummy.length);
            int startIndex = 0;
            // Search the FAT and return the file information.
            for (int i = 0; i < 28; i++) {
                byte[] parts = Arrays.copyOfRange(Disk.read(0), 18 * i, (18 * (i + 1)));
                byte[] dummyParts = Arrays.copyOfRange(parts, 0, 8);
                if (Arrays.equals(nameBytes, dummyParts)) {
                    dummyParts = Arrays.copyOfRange(parts, 8, 11);
                    startIndex = bytesToInt(dummyParts);
                    // update FAT
                    for(int j = 18 * i; j < (18 * (i + 1)); j++){
                        Disk.blocks[0][j] = (byte) -1;
                    }
                    isFound = true;
                    break;
                }
            }
            if(!isFound){
                System.out.println("File not found!");
                return;
            }

            int index = startIndex;
            byte[] nextBlock;
            while(index != -1){
                // update bitmap
                Disk.blocks[1][index] = (byte) 0;
                // delete the file data
                for(int i = 0; i < Disk.BLOCKS_SIZE - 3; i++){
                    Disk.blocks[index][i] = (byte) 0;
                }
                nextBlock = Arrays.copyOfRange(Disk.read(index), Disk.BLOCKS_SIZE - 3, Disk.BLOCKS_SIZE);
                // delete pointer
                for(int i = (Disk.BLOCKS_SIZE - 3); i < Disk.BLOCKS_SIZE; i++){
                    Disk.blocks[index][i] = (byte) 0;
                }
                index = bytesToInt(nextBlock);
            }
        }

        @Override
        public void copyFile(String sourceName, String destName) {
            boolean isFound = false;
            // convert name to bytes
            byte[] nameBytes = new byte[8];
            byte fillValue = -1;
            Arrays.fill(nameBytes, fillValue);
            byte[] dummy = sourceName.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(dummy, 0, nameBytes, 0, dummy.length);
            int startIndex = 0;
            int blockSize = 0;
            int dataSize = 0;
            // Search the FAT and return the file information.
            for (int i = 0; i < 28; i++) {
                byte[] parts = Arrays.copyOfRange(Disk.read(0), 18 * i, (18 * (i + 1)));
                byte[] dummyParts = Arrays.copyOfRange(parts, 0, 8);
                if (Arrays.equals(nameBytes, dummyParts)) {
                    dummyParts = Arrays.copyOfRange(parts, 8, 11);
                    startIndex = bytesToInt(dummyParts);
                    dummyParts = Arrays.copyOfRange(parts,14,18);
                    dataSize = bytesToInt(dummyParts);
                    isFound = true;
                    break;
                }
            }
            if(!isFound){
                System.out.println("File not found!");
                return;
            }

            // get the data from disk
            byte[] copyByte = new byte[dataSize];
            int index = startIndex;
            int ptr = 0;
            byte[] nextBlock;
            while(index != -1){
                if(dataSize > Disk.BLOCKS_SIZE - 3){
                    System.arraycopy(Disk.blocks[index], 0, copyByte, ptr, Disk.BLOCKS_SIZE - 3);
                    dataSize -= Disk.BLOCKS_SIZE - 3;
                    ptr += Disk.BLOCKS_SIZE - 3;
                } else {
                    System.arraycopy(Disk.blocks[index], 0, copyByte, ptr, dataSize);
                }

                nextBlock = Arrays.copyOfRange(Disk.blocks[index], Disk.BLOCKS_SIZE - 3, Disk.BLOCKS_SIZE);
                index = bytesToInt(nextBlock);
            }

            Path currentDirectory = Paths.get(System.getProperty("user.dir"));
            Path filePath = currentDirectory.resolve(destName);
            if(!Files.isRegularFile(filePath)){
                System.out.println("Error: The destination file must be in the current directory.");
                return;
            }

            try(FileOutputStream outputStream = new FileOutputStream(destName)) {
                outputStream.write(copyByte);
                System.out.println("File copy successfully at: " + filePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void displayFAT(byte[] data) {
            for(int i = 0; i < 28; i++) {
                byte[] parts = Arrays.copyOfRange(data, 18 * i, (18 * (i + 1)));
                if (parts[0] != -1) {
                    System.out.println(extractName(parts) + " " + extractStartBlock(parts) + " " + extractEndBlock(parts));
                }
            }
        }

    }

    static class Indexed extends AllocationMethod{

        @Override
        public boolean createFile(String fileName) {
            try {
                // set up the file
                byte[] fileDataBytes = toBytes(fileName); // data
                byte[] fileSizeBytes = String.valueOf(fileDataBytes.length).getBytes(StandardCharsets.UTF_8);
                String[] name = fileName.split("\\.");
                byte[] fileNameBytes = name[0].getBytes(StandardCharsets.UTF_8); // name
                // calculate the number of blocks
                int blockNeed = (int) Math.ceil(fileDataBytes.length / 512.0);
                if((blockNeed + 1) <= numBlockFree()){
                    byte[] index = String.valueOf(findBlockFree(blockNeed)).getBytes(StandardCharsets.UTF_8);
                    // Combine all the file information
                    byte fillValue = -1;
                    byte[] fileInfor = new byte[15];
                    Arrays.fill(fileInfor, fillValue); // set the fileInfor to null
                    System.arraycopy(fileNameBytes, 0, fileInfor, 0, fileNameBytes.length);
                    System.arraycopy(index, 0, fileInfor, 8, index.length);
                    System.arraycopy(fileSizeBytes, 0, fileInfor, 11, fileSizeBytes.length);
                    // add file information to FAT
                    System.arraycopy(fileInfor, 0, Disk.blocks[0], findFATFree(15), fileInfor.length);
                    int dummyBlock = blockNeed;
                    byte[] indexBlock = new byte[blockNeed*3];
                    Disk.write(bytesToInt(index), indexBlock); // reserve indexBlock
                    // write data file to disk
                    for(int i = 0; i < blockNeed; i++){
                        int currentBlock = findBlockFree(dummyBlock);
                        dummyBlock--;
                        System.arraycopy(String.valueOf(currentBlock).getBytes(StandardCharsets.UTF_8), 0, indexBlock, i*3, String.valueOf(currentBlock).getBytes(StandardCharsets.UTF_8).length);
                        byte[] dummy = Arrays.copyOfRange(fileDataBytes, ((Disk.BLOCKS_SIZE) * i), ((Disk.BLOCKS_SIZE) * (i + 1)));
                        byte[] partitionFile = new byte[dummy.length];
                        Arrays.fill(partitionFile, (byte) 0);
                        partitionFile = Arrays.copyOfRange(dummy, 0,partitionFile.length);
                        Disk.write(currentBlock, partitionFile);
                    }
                    Disk.write(bytesToInt(index), indexBlock);
                    return true;
                } else {
                    System.out.println("Not enough space.");
                    return false;
                }
            } catch (IOException e){
                e.printStackTrace();
            }
            return false;
        }

        @Override
        public void getData(String name) {
            boolean isFound = false;
            // convert name to bytes
            byte[] nameBytes = new byte[8];
            byte fillValue = -1;
            Arrays.fill(nameBytes, fillValue);
            byte[] dummy = name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(dummy, 0, nameBytes, 0, dummy.length);
            int indexTable = 0;
            int dataSize = 0;
            // Search the FAT and return the file information.
            for (int i = 0; i < 28; i++) {
                byte[] parts = Arrays.copyOfRange(Disk.read(0), 15 * i, (15 * (i + 1)));
                byte[] dummyParts = Arrays.copyOfRange(parts, 0, 8);
                if (Arrays.equals(nameBytes, dummyParts)) {
                    dummyParts = Arrays.copyOfRange(parts, 8, 11);
                    indexTable = bytesToInt(dummyParts);
                    dummyParts = Arrays.copyOfRange(parts,11,15);
                    dataSize = bytesToInt(dummyParts);
                    isFound = true;
                    break;
                }
            }
            if(!isFound){
                System.out.println("File not found!");
                return;
            }
            byte[] dataBytes = new byte[dataSize];
            byte[] partIndex = new byte[3];
            int numBlocks = (int) Math.ceil(dataSize / 512.0);
            System.arraycopy(Disk.read(indexTable), 0, partIndex, 0, partIndex.length);
            int index = bytesToInt(partIndex);
            int subIndex = 1;
            int ptr = 0;
            while(subIndex <= numBlocks){
                if(dataSize > Disk.BLOCKS_SIZE){
                    System.arraycopy(Disk.read(index), 0, dataBytes, ptr, Disk.BLOCKS_SIZE);
                    dataSize -= Disk.BLOCKS_SIZE;
                    ptr += Disk.BLOCKS_SIZE;
                } else {
                    System.arraycopy(Disk.read(index), 0, dataBytes, ptr, dataSize);
                }
                System.arraycopy(Disk.read(indexTable), subIndex * 3, partIndex, 0, partIndex.length);
                subIndex++;
                if(subIndex <= numBlocks) {
                    index = bytesToInt(partIndex);
                }
            }
            System.out.println(Arrays.toString(dataBytes));
        }

        @Override
        public void deleteFile(String name) {
            boolean isFound = false;
            // convert name to bytes
            byte[] nameBytes = new byte[8];
            byte fillValue = -1;
            Arrays.fill(nameBytes, fillValue);
            byte[] dummy = name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(dummy, 0, nameBytes, 0, dummy.length);
            int indexTable = 0;
            int dataSize = 0;
            // Search the FAT and return the file information.
            for (int i = 0; i < 28; i++) {
                byte[] parts = Arrays.copyOfRange(Disk.read(0), 15 * i, (15 * (i + 1)));
                byte[] dummyParts = Arrays.copyOfRange(parts, 0, 8);
                if (Arrays.equals(nameBytes, dummyParts)) {
                    dummyParts = Arrays.copyOfRange(parts, 8, 11);
                    indexTable = bytesToInt(dummyParts);
                    dummyParts = Arrays.copyOfRange(parts,11,15);
                    dataSize = bytesToInt(dummyParts);
                    // update FAT
                    for(int j = 15 * i; j < (15 * (i + 1)); j++){
                        Disk.blocks[0][j] = fillValue;
                    }
                    isFound = true;
                    break;
                }
            }
            if(!isFound){
                System.out.println("File not found!");
                return;
            }
            int numBlocks = (int) Math.ceil(dataSize / 512.0);
            byte[] partIndex = new byte[3];
            System.arraycopy(Disk.read(indexTable), 0, partIndex, 0, partIndex.length);
            int index = bytesToInt(partIndex);
            int subIndex = 1;
            int dummyIndex;
            // delete file data
            while(subIndex <= numBlocks){
                Arrays.fill(Disk.read(index), (byte) 0);
                // update bitmap
                Disk.blocks[1][index] = (byte) 0;
                dummyIndex = subIndex;
                System.arraycopy(Disk.read(indexTable), subIndex * 3, partIndex, 0, partIndex.length);
                subIndex++;
                if(subIndex <= numBlocks) {
                    index = bytesToInt(partIndex);
                }
                // update index table
                Disk.blocks[indexTable][dummyIndex] = (byte) 0;
            }
            // delete index table from bit map
            Disk.blocks[1][indexTable] = (byte) 0;
        }

        @Override
        public void copyFile(String sourceName, String destName) {
            boolean isFound = false;
            // convert name to bytes
            byte[] nameBytes = new byte[8];
            byte fillValue = -1;
            Arrays.fill(nameBytes, fillValue);
            byte[] dummy = sourceName.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(dummy, 0, nameBytes, 0, dummy.length);
            int indexTable = 0;
            int dataSize = 0;
            // Search the FAT and return the file information.
            for (int i = 0; i < 28; i++) {
                byte[] parts = Arrays.copyOfRange(Disk.read(0), 15 * i, (15 * (i + 1)));
                byte[] dummyParts = Arrays.copyOfRange(parts, 0, 8);
                if (Arrays.equals(nameBytes, dummyParts)) {
                    dummyParts = Arrays.copyOfRange(parts, 8, 11);
                    indexTable = bytesToInt(dummyParts);
                    dummyParts = Arrays.copyOfRange(parts,11,15);
                    dataSize = bytesToInt(dummyParts);
                    isFound = true;
                    break;
                }
            }
            if(!isFound){
                System.out.println("File not found!");
                return;
            }

            byte[] copyByte = new byte[dataSize];

            // get the data from disk
            byte[] partIndex = new byte[3];
            int numBlocks = (int) Math.ceil(dataSize / 512.0);
            System.arraycopy(Disk.read(indexTable), 0, partIndex, 0, partIndex.length);
            int index = bytesToInt(partIndex);
            int subIndex = 1;
            int ptr = 0;
            while(subIndex <= numBlocks){
                if(dataSize > Disk.BLOCKS_SIZE){
                    System.arraycopy(Disk.read(index), 0, copyByte, ptr, Disk.BLOCKS_SIZE);
                    dataSize -= Disk.BLOCKS_SIZE;
                    ptr += Disk.BLOCKS_SIZE;
                } else {
                    System.arraycopy(Disk.read(index), 0, copyByte, ptr, dataSize);
                }
                System.arraycopy(Disk.read(indexTable), subIndex * 3, partIndex, 0, partIndex.length);
                subIndex++;
                if(subIndex <= numBlocks) {
                    index = bytesToInt(partIndex);
                }
            }

            Path currentDirectory = Paths.get(System.getProperty("user.dir"));
            Path filePath = currentDirectory.resolve(destName);
            if(!Files.isRegularFile(filePath)){
                System.out.println("Error: The destination file must be in the current directory.");
                return;
            }

            try(FileOutputStream outputStream = new FileOutputStream(destName)) {
                outputStream.write(copyByte);
                System.out.println("File copy successfully at: " + filePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void displayFAT(byte[] data) {
            for(int i = 0; i < 28; i++) {
                byte[] parts = Arrays.copyOfRange(data, 15 * i, (15 * (i + 1)));
                if (parts[0] != -1) {
                    System.out.println(extractName(parts) + " " + extractStartBlock(parts));
                }
            }
        }
    }

    static int numBlockFree() {
        int count = 0;
        for(int i = 0; i < Disk.NUM_BLOCKS; i++){
            if(Disk.blocks[1][i] == 0){
                count++;
            }
        }
        return count;
    }

    // find the first free block
    static int findBlockFree(int blockNeed) {
        int count = 0;
        for(int i = 0; i < Disk.NUM_BLOCKS; i++) {
            if(Disk.blocks[1][i] == 0){
                count++;
                if(count == blockNeed) {
                    return i - blockNeed + 1;
                }
            }
        }
        return -1;
    }

    // extract file ending block from next 3 bytes
    static String extractEndBlock(byte[] data){
        byte fillSpace = 32;
        byte[] endBlock;
        endBlock = Arrays.copyOfRange(data, 11, 14);
        for(int i = 0; i < endBlock.length; i++){
            if(endBlock[i] == -1){
                endBlock[i] = fillSpace;
            }
        }
        return new String(endBlock, StandardCharsets.UTF_8);
    }

    // find the first free space in file allocation table
    static int findFATFree(int offset) {
        for(int i = 0; i < Disk.NUM_BLOCKS; i+= offset) {
            if(Disk.blocks[0][i] == -1){
                return i;
            }
        }
        return -1;
    }

    // convert file to bytes
    static byte[] toBytes(String fileName) throws IOException {
        Path path = Paths.get(fileName);
        return Files.readAllBytes(path);
    }

    // convert bytes to int
    static int bytesToInt(byte[] byteArray) {
        byte[] dummy = new byte[byteArray.length];
        for(int i = 0; i < byteArray.length; i++){
            if(byteArray[i] != -1){
                dummy[i] = byteArray[i];
            }
        }
        return Integer.parseInt(new String(dummy, StandardCharsets.UTF_8).trim());
    }

    // extract file name from 8 first bytes
    static String extractName(byte[] data){
        byte fillSpace = 32;
        byte[] name = new byte[8];
        Arrays.fill(name, fillSpace);
        for(int i = 0; i < name.length; i++){
            if(data[i] != -1){
                name[i] = data[i];
            }
        }
        return new String(name, StandardCharsets.UTF_8);
    }

    // extract file starting block from next 3 bytes
    static String extractStartBlock(byte[] data){
        byte fillSpace = 32;
        byte[] startBlock;
        startBlock = Arrays.copyOfRange(data, 8, 11);
        for(int i = 0; i < startBlock.length; i++){
            if(startBlock[i] == -1){
                startBlock[i] = fillSpace;
            }
        }
        return new String(startBlock, StandardCharsets.UTF_8);
    }

    // Factory method to create AllocationMethod based on user input
    public static AllocationMethod createAllocationMethod(String method) {
        switch (method.toLowerCase()) {
            case "contiguous":
                return new Contiguous();
            case "chained":
                return new Chained();
            case "indexed":
                return new Indexed();
            default:
                throw new IllegalArgumentException("Invalid allocation method: " + method);
        }
    }
}

class UserInterface {

    FileSystem.AllocationMethod allocationMethod;
    
    public UserInterface(String allocationType) {
        // Create the corresponding AllocationMethod based on user input
        this.allocationMethod = FileSystem.createAllocationMethod(allocationType);
    }

    // check the length of file name
    boolean isValid(String name){
        return name.length() <= 8 && name.equals(name.toLowerCase());
    }

    // display file
    void displayFile(String name){
        allocationMethod.getData(name);
    }

    // display the free space bitmap
    void displayBitmap(){
        allocationMethod.displayBitmap();
    }

    // display a disk block
    void displayBlock(int blockNum){
        allocationMethod.displayBlock(blockNum);
    }

    // display the file allocation table
    void displayFAT(){
        allocationMethod.displayFAT(Disk.blocks[0]);
    }

    public void showMenu(){
        Scanner in = new Scanner(System.in);
        int userChoice = 0;
        while(userChoice != 8) {
            System.out.println();
            System.out.println(
                "1) Display a file\n" +
                "2) Display the file table\n" +
                "3) Display the free space bitmap\n" +
                "4) Display a disk block\n" +
                "5) Copy a file from the simulation to a file on the real system\n" +
                "6) Copy a file from the real system to a file in the simulation\n" +
                "7) Delete a file\n" +
                "8) Exit"
            );
            System.out.print("Choice: ");
            userChoice = in.nextInt();

            // perform action based on user choice
            switch (userChoice){
                case 1:
                    String fileName;
                    in.nextLine();
                    System.out.print("File name: ");
                    fileName = in.nextLine();
                    if(isValid(fileName)){
                        displayFile(fileName);
                    } else {
                        System.out.println("File names should be up to 8 characters.  Names should only have lowercase letters.");
                    }

                    break;

                case 2:
                    displayFAT();
                    break;

                case 3:
                    displayBitmap();
                    break;

                case 4:
                    System.out.print("Block Number: ");
                    int blockNum = in.nextInt();
                    displayBlock(blockNum);
                    break;

                case 5:
                    String sourceName;
                    String destName;
                    in.nextLine();
                    System.out.print("Copy from: ");
                    sourceName = in.nextLine();
                    System.out.print("Copy to: ");
                    destName = in.nextLine();
                    if(isValid(sourceName)){
                        allocationMethod.copyFile(sourceName, destName);
                    } else {
                        System.out.println("File names should be up to 8 characters.  Names should only have lowercase letters.");
                    }
                    break;

                case 6:
                    String sysFileName;
                    in.nextLine();
                    System.out.print("Copy from: ");
                    sysFileName = in.nextLine();
                    // Split the text based on ". "
                    String[] fileNameparts = sysFileName.split("\\.");
                    if(isValid(fileNameparts[0])) {
                        if(allocationMethod.createFile(sysFileName)){
                            System.out.println("Copy to: " + fileNameparts[0]);
                            System.out.println("File " + sysFileName + " copied.");
                        }
                    } else {
                        System.out.println("File names should be up to 8 characters.  Names should only have lowercase letters.");
                    }
                    break;

                case 7:
                    String sFileName;
                    in.nextLine();
                    System.out.print("File name: ");
                    sFileName = in.nextLine();
                    if(isValid(sFileName)){
                        allocationMethod.deleteFile(sFileName);
                    } else {
                        System.out.println("File names should be up to 8 characters.  Names should only have lowercase letters.");
                    }
                    break;

                case 8:
                    break;

                default:
                    System.err.println("Input is not recognize.");
                    break;
            }

        }
    }
}

public class Project3 {
    public static void main(String[] args) throws IOException {
        String allocationType = args[0];

        Disk disk = new Disk();

        UserInterface UI = new UserInterface(allocationType);
        UI.showMenu();
    }
}
