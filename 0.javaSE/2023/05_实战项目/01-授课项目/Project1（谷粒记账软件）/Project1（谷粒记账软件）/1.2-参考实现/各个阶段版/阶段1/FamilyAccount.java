
public class FamilyAccount {

	public static void main(String[] args) {

		boolean isFlag = true;

		do {
			System.out
					.println("\n-----------------������֧�������-----------------\n");
			System.out.println("                   1 ��֧��ϸ");
			System.out.println("                   2 �Ǽ�����");
			System.out.println("                   3 �Ǽ�֧��");
			System.out.println("                   4 ��    ��\n");
			System.out.print("                   ��ѡ��(1-4)��");

			// Ҫ���û�����1-4ѡ��
			char menu = Utility.readMenuSelection();
			switch (menu) {
			case '1':
				System.out.println("��ʾ����");
				break;
			case '2':
				System.out.println("�������");
				break;
			case '3':
				System.out.println("֧�����");
				break;
			case '4':
				System.out.print("ȷ���Ƿ��˳�(Y/N)��");
				char exit = Utility.readConfirmSelection();
				if(exit == 'Y'){
					isFlag = false;
				}
				
				break;

			}

		} while (isFlag);

	}

}
