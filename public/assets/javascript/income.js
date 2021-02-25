async function addIncomeHandler(event) {
    const name = document.querySelector('#incomeName').value;
    const amount = document.querySelector('#incomeAmount').value;

    const response = await fetch('/api/income', {
        method: 'POST',
        body: JSON.stringify({
            name,
            amount
        }),
        headers: {
            'Content-Type': 'application/json'
        }
    });

    if (response.ok) {
        document.location.reload();
    } else {
        alert(response.statusText)
    }
};

async function getIncomeHandler(event) {

    const id = window.location.toString().split('/')[
        window.location.toString().split('/').length - 1
    ];

    const response = await fetch(`/api/users/${id}`, {
        method: 'GET',
        headers: {
            'Content-Type': 'application/json'
        }
    });

    if (response.ok) {
        response.json().then(text => {
            const incomeDiv = document.querySelector('.income-output-wrapper');
            const totalIncomeDiv = document.querySelector('.total-income');
            totalIncomeDiv.innerHTML = ""

            const incomeArr = text.incomes;
            let totalIncome = 0;
            incomeArr.forEach(income => {

                let incomeItemsDiv = document.createElement('div');
                let incomeName = document.createElement('p');
                let incomeAmount = document.createElement('p');
                let incomeDelete = document.createElement('button');

                incomeName.innerHTML = income.name;
                incomeAmount.innerHTML = income.amount;
                incomeDelete.innerHTML = 'Delete'

                incomeDelete.classList.add('delete-btn');
                incomeItemsDiv.classList.add('income-items');

                incomeDelete.setAttribute('id', `${income.id}`)

                incomeItemsDiv.append(incomeName);
                incomeItemsDiv.append(incomeAmount);
                incomeItemsDiv.append(incomeDelete)
                incomeDiv.append(incomeItemsDiv);

                totalIncome = totalIncome + parseInt(income.amount);
            })

            totalIncomeDiv.innerHTML = `Total Incomes: ${totalIncome}`;
            async function deleteIncome(event) {

                const id = event.target.getAttribute('id');

                const response = await fetch(`/api/income/${id}`, {
                    method: 'DELETE',
                    headers: {
                        'Content-Type': 'application/json'
                    }
                })
                if (response.ok) {
                    document.location.reload();
                } else {
                    alert(response.statusText)
                }
            }

            document.querySelectorAll('.delete-btn').forEach(btn => {
                btn.addEventListener('click', deleteIncome)
            });

        })
    } else {
        alert(response.statusText)
    }
};

getIncomeHandler();


document.querySelector('.add-income').addEventListener('click', addIncomeHandler);