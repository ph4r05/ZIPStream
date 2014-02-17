#include <unistd.h>
#define _GNU_SOURCE 
#include <fcntl.h>  
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <libgen.h>
#include <string.h>

void main() {

	int pipeA[2], pipeB[2], ret;
	if(pipe(pipeA) == -1||pipe(pipeB) == -1) {
	  perror("Pipe failed");
	  exit(1);
	}

	// get location of this program image.
	char * dname;
	char loc[1024];
	char pathJar[1024];
	char wrapCmd[1024];

	int lsize=readlink("/proc/self/exe", loc, 1024);
	if (lsize==-1){
		perror("Cannot determine correct path");
		exit(2);

	} 
	
	loc[lsize] = 0;
	fprintf(stderr, "Current executable: '%s'", loc);
	dname=dirname(loc);	// dname is same as loc, do not free!
	fprintf(stderr, " dir: '%s'\n", dname);
	
	strcpy(pathJar, dname);
	strcat(pathJar, "/ZIPStream-1.0-SNAPSHOT.jar");

	strcpy(wrapCmd, "/bin/bash ");
	strcat(wrapCmd, dname);
	strcat(wrapCmd, "/wrapper.sh <<INPUTAPK>>");
	
	fprintf(stderr, "Jar: '%s' cmd: '%s'\n\n", pathJar, wrapCmd);

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
	
		// previously used (did not work, now use wrapper):
		// "--cmd", "/bin/bash -c 'cd /root/program/use_jdbc; /bin/java -jar /root/program/use_jdbc/ApkAttack.jar <<INPUTAPK>>'", 
		execlp("/bin/java", "/bin/java", "-cp", pathJar, 
			"cz.muni.fi.xklinec.zipstream.Mallory",
			"-f", "1" ,
                        "-e", ".*gif$",
			"--padd-extra", "512000",
			"--cmd", wrapCmd,
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

		wait();
		exit(0);
	}
}
