#include <unistd.h>
#define _GNU_SOURCE 
#include <fcntl.h>  
#include <unistd.h>
#include <stdlib.h>

void main() {

	int pipeA[2], pipeB[2], ret;
	if(pipe(pipeA) == -1||pipe(pipeB) == -1) {
	  perror("Pipe failed");
	  exit(1);
	}

	if (fork()==0){
		// Close stdin of the program, duplicate input side of pipe to stdin
		close(0);
		dup2(pipeA[0], 0);
		
		// Close stdout of the program, duplicate output side of pipe to stdout
		// stdout does not need to be redirected, can be directly connected to output.
		// No need to process output of this command thus no need for pipe.
		//close(1);
		//dup2(pipeB[1], 1);
		
		// Closing some shit
		close(pipeA[0]);
		close(pipeA[1]);
		close(pipeB[0]);
		close(pipeB[1]);

		execlp("/bin/java", "/bin/java", "-cp", "ZIPStream-1.0-SNAPSHOT.jar", 
			"cz.muni.fi.xklinec.zipstream.Mallory",
			"-f", "1" ,
			"--padd-extra", "512000",
			//"--cmd", "/bin/bash -c 'cd /root/program/use_jdbc; /bin/java -jar /root/program/use_jdbc/ApkAttack.jar <<INPUTAPK>>'", 
			"--cmd", "/bin/bash wrapper.sh <<INPUTAPK>>",
			 NULL);
		
		perror("execvp of ./p failed");
		exit(1);
	} else {	// parent
		close(pipeA[0]);
		close(pipeB[0]);
		close(pipeB[1]);
		//dup2(pipeA[1], 0);
		//write(pipeA[1], "test", 5);
		
		int readb=0;
		char reading_buf[128];
	        while((readb=read(0, reading_buf, 128)) > 0){
	            write(pipeA[1], reading_buf, readb); // 1 -> stdout
	        }
		
		close(pipeA[0]);
		close(pipeA[1]);
		close(pipeB[0]);
		close(pipeB[1]);

		// we write to pipeA[1], read from pipeB[0] 
		//socket_nonblocking(pipeA[1]);
		//socket_nonblocking(pipeB[0]);
		wait();
		exit(0);
	}
}
